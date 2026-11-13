#ifndef LINUX_DESKTOP_WL_SELFTEST_H
#define LINUX_DESKTOP_WL_SELFTEST_H

#include <stdbool.h>

bool wl_selftest_send_frame(const char *runtime_dir, int width, int height);

#endif
