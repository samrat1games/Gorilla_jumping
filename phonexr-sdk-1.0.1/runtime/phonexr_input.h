/*
 * PhoneXR input feed for native games (C/C++).
 *
 * Обычный ввод игра получает через OpenXR. Этот заголовок нужен для сырых данных PhoneXR:
 * положение ладоней в кадре, жесты и кнопки Joy-Con.
 *
 * Использование:
 *     #define PHONEXR_INPUT_IMPLEMENTATION
 *     #include "phonexr_input.h"
 *
 *     struct phonexr_input in;
 *     phonexr_input_open(&in, PHONEXR_INPUT_PORT);
 *     struct phonexr_state state;
 *     if (phonexr_input_poll(&in, &state)) { ... }
 *     phonexr_input_close(&in);
 *
 * Порт занимает один клиент: если данных нет, их уже читает другое приложение.
 */
#ifndef PHONEXR_INPUT_H
#define PHONEXR_INPUT_H

#include <stdbool.h>
#include <stdint.h>

#define PHONEXR_INPUT_PORT 42425

/* Биты phonexr_hand.buttons. */
#define PHONEXR_BUTTON_PRIMARY (1u << 0)     /* A / X */
#define PHONEXR_BUTTON_SECONDARY (1u << 1)   /* B / Y */
#define PHONEXR_BUTTON_TRIGGER (1u << 2)
#define PHONEXR_BUTTON_SQUEEZE (1u << 3)
#define PHONEXR_BUTTON_MENU (1u << 4)
#define PHONEXR_BUTTON_STICK_CLICK (1u << 5)
#define PHONEXR_BUTTON_SYSTEM (1u << 6)

/* Биты phonexr_state.flags. */
#define PHONEXR_FLAG_SIX_DOF (1u << 0)   /* положение берётся с камеры */
#define PHONEXR_FLAG_HANDS_ONLY (1u << 1) /* жесты пальцев ничего не нажимают */

struct phonexr_hand
{
	bool present;              /* рука видна камере или подключён Joy-Con */
	bool fist, index, thumb;   /* жесты; в режиме «только руки» всегда false */
	float x, y, z;             /* ладонь в кадре: x, y в диапазоне 0..1, z — близость к камере */
	float qx, qy, qz, qw;      /* поворот от Joy-Con, иначе единичный кватернион */
	uint32_t buttons;
	float stick_x, stick_y;    /* стик Joy-Con, -1..1 (PH5; в PH4 — 0) */
	bool pinch, palm_to_face;  /* щипок и ладонь к лицу (PH5; в PH4 — false) */
};

struct phonexr_state
{
	struct phonexr_hand left;
	struct phonexr_hand right;
	uint32_t flags;
};

struct phonexr_input
{
	int socket_fd;
};

/* Возвращает true, если удалось занять порт. */
bool phonexr_input_open(struct phonexr_input *input, int port);

/* Забирает самый свежий пакет. Возвращает false, если новых данных нет. Не блокирует. */
bool phonexr_input_poll(struct phonexr_input *input, struct phonexr_state *out_state);

void phonexr_input_close(struct phonexr_input *input);

#ifdef PHONEXR_INPUT_IMPLEMENTATION

#include <arpa/inet.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

bool
phonexr_input_open(struct phonexr_input *input, int port)
{
	input->socket_fd = socket(AF_INET, SOCK_DGRAM, 0);
	if (input->socket_fd < 0) {
		return false;
	}
	int flags = fcntl(input->socket_fd, F_GETFL, 0);
	(void)fcntl(input->socket_fd, F_SETFL, flags | O_NONBLOCK);

	struct sockaddr_in address;
	memset(&address, 0, sizeof(address));
	address.sin_family = AF_INET;
	address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	address.sin_port = htons((uint16_t)port);
	if (bind(input->socket_fd, (struct sockaddr *)&address, sizeof(address)) != 0) {
		close(input->socket_fd);
		input->socket_fd = -1;
		return false;
	}
	return true;
}

bool
phonexr_input_poll(struct phonexr_input *input, struct phonexr_state *out_state)
{
	if (input->socket_fd < 0) {
		return false;
	}
	char packet[512];
	bool received = false;
	ssize_t length;
	/* Пакеты идут 60 раз в секунду: берём последний, накопившиеся пропускаем. */
	while ((length = recv(input->socket_fd, packet, sizeof(packet) - 1, 0)) > 0) {
		packet[length] = '\0';
		struct phonexr_state parsed;
		memset(&parsed, 0, sizeof(parsed));
		int lp, lf, li, lt, lb, rp, rf, ri, rt, rb, fl;
		int lpinch = 0, lpalm = 0, rpinch = 0, rpalm = 0;
		struct phonexr_hand *l = &parsed.left;
		struct phonexr_hand *r = &parsed.right;
		/* PH5 (current): each hand also has the Joy-Con stick; pinch and palm-to-face at the end. */
		int count = sscanf(packet,
		                   "PH5 %d %d %d %d %f %f %f %f %f %f %f %d %f %f %d %d %d %d %f %f %f %f %f %f %f %d %f %f %d %d %d %d %d",
		                   &lp, &lf, &li, &lt, &l->x, &l->y, &l->z, &l->qx, &l->qy, &l->qz, &l->qw, &lb, &l->stick_x, &l->stick_y,
		                   &rp, &rf, &ri, &rt, &r->x, &r->y, &r->z, &r->qx, &r->qy, &r->qz, &r->qw, &rb, &r->stick_x, &r->stick_y,
		                   &fl, &lpinch, &lpalm, &rpinch, &rpalm);
		if (count < 29) {
			memset(&parsed, 0, sizeof(parsed));
			count = sscanf(packet,
			               "PH4 %d %d %d %d %f %f %f %f %f %f %f %d %d %d %d %d %f %f %f %f %f %f %f %d %d",
			               &lp, &lf, &li, &lt, &l->x, &l->y, &l->z, &l->qx, &l->qy, &l->qz, &l->qw, &lb,
			               &rp, &rf, &ri, &rt, &r->x, &r->y, &r->z, &r->qx, &r->qy, &r->qz, &r->qw, &rb, &fl);
			if (count != 25) {
				continue;
			}
		}
		l->pinch = lpinch != 0;
		l->palm_to_face = lpalm != 0;
		r->pinch = rpinch != 0;
		r->palm_to_face = rpalm != 0;
		l->present = lp != 0;
		l->fist = lf != 0;
		l->index = li != 0;
		l->thumb = lt != 0;
		l->buttons = (uint32_t)lb;
		r->present = rp != 0;
		r->fist = rf != 0;
		r->index = ri != 0;
		r->thumb = rt != 0;
		r->buttons = (uint32_t)rb;
		parsed.flags = (uint32_t)fl;
		*out_state = parsed;
		received = true;
	}
	return received;
}

void
phonexr_input_close(struct phonexr_input *input)
{
	if (input->socket_fd >= 0) {
		close(input->socket_fd);
		input->socket_fd = -1;
	}
}

#endif /* PHONEXR_INPUT_IMPLEMENTATION */
#endif /* PHONEXR_INPUT_H */
