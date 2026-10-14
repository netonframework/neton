#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <sys/socket.h>

int listen(int fd, int backlog) {
    int (*next)(int, int) = dlsym(RTLD_NEXT, "listen");
    fprintf(stderr, "backlog probe: %d -> 65535\n", backlog);
    return next(fd, 65535);
}
