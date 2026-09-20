-- Gorilla Jumping: серверы (комнаты) и счётчик онлайна.
-- Запустить один раз: Supabase → SQL Editor → вставить → Run. Повторный запуск безопасен.
--
-- Как это работает:
--  * gorillajump_servers — список серверов в меню игры. Добавлять/переименовывать/скрывать
--    серверы — обычными insert/update в этой таблице (из SQL Editor или Table Editor).
--  * gorillajump_sessions — кто где играет; игра раз в 10 с вызывает gorillajump_heartbeat.
--    Напрямую таблицу из приложения не видно и не изменить: только через функции ниже.
--  * Движения игроков идут через Supabase Realtime (broadcast, канал «gj:<сервер>:<карта>»),
--    в базу не пишутся. В Realtime → Settings должно быть включено «Allow public access»
--    (по умолчанию включено), иначе публичные каналы не пустят.

-- ---------- Серверы ----------
create table if not exists public.gorillajump_servers (
  id          text primary key check (id ~ '^[a-z0-9_-]{1,32}$'),
  name        text not null check (char_length(name) between 1 and 32),
  region      text not null default 'EU',
  max_players int  not null default 10 check (max_players between 2 and 20),
  is_public   boolean not null default true,
  sort        int  not null default 0,
  created_at  timestamptz not null default now()
);
alter table public.gorillajump_servers enable row level security;
drop policy if exists "gorillajump servers readable" on public.gorillajump_servers;
create policy "gorillajump servers readable" on public.gorillajump_servers
  for select to anon, authenticated using (is_public);

insert into public.gorillajump_servers (id, name, region, max_players, sort) values
  ('main',   'Главный',   'EU', 10, 0),
  ('eu-2',   'Европа 2',  'EU', 10, 1),
  ('ru-1',   'Россия 1',  'RU', 10, 2),
  ('casual', 'Казуал',    'EU', 10, 3)
on conflict (id) do nothing;

-- ---------- Кто сейчас играет ----------
create table if not exists public.gorillajump_sessions (
  player_id uuid primary key,
  server_id text not null references public.gorillajump_servers (id) on delete cascade,
  map       text not null check (map in ('forest', 'cave', 'canyon', 'mountain', 'city')),
  name      text not null default '' check (char_length(name) <= 24),
  last_seen timestamptz not null default now()
);
create index if not exists gorillajump_sessions_server on public.gorillajump_sessions (server_id, last_seen);
alter table public.gorillajump_sessions enable row level security;
-- Политик нет: anon не читает и не пишет таблицу напрямую, только через функции.

-- Игрок отмечается, что он на сервере; заодно чистим тех, кто давно пропал.
create or replace function public.gorillajump_heartbeat(p_player uuid, p_server text, p_map text, p_name text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if not exists (select 1 from gorillajump_servers where id = p_server and is_public) then
    raise exception 'unknown server %', p_server;
  end if;
  insert into gorillajump_sessions (player_id, server_id, map, name, last_seen)
  values (p_player, p_server, p_map, left(coalesce(p_name, ''), 24), now())
  on conflict (player_id) do update
    set server_id = excluded.server_id, map = excluded.map, name = excluded.name, last_seen = now();
  delete from gorillajump_sessions where last_seen < now() - interval '2 minutes';
end;
$$;

create or replace function public.gorillajump_leave(p_player uuid)
returns void
language sql
security definer
set search_path = public
as $$
  delete from gorillajump_sessions where player_id = p_player;
$$;

-- Список серверов для меню: с числом игроков онлайн (отмечались за последние 30 с).
create or replace function public.gorillajump_server_list()
returns table (id text, name text, region text, max_players int, online int)
language sql
stable
security definer
set search_path = public
as $$
  select s.id, s.name, s.region, s.max_players,
         (select count(*) from gorillajump_sessions x
           where x.server_id = s.id and x.last_seen > now() - interval '30 seconds')::int
  from gorillajump_servers s
  where s.is_public
  order by s.sort, s.name;
$$;

revoke all on function public.gorillajump_heartbeat(uuid, text, text, text) from public;
revoke all on function public.gorillajump_leave(uuid) from public;
revoke all on function public.gorillajump_server_list() from public;
grant execute on function public.gorillajump_heartbeat(uuid, text, text, text) to anon, authenticated;
grant execute on function public.gorillajump_leave(uuid) to anon, authenticated;
grant execute on function public.gorillajump_server_list() to anon, authenticated;
