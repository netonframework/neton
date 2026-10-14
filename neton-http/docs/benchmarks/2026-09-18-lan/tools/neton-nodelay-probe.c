#define _GNU_SOURCE
#include <dlfcn.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>

static int configure(int fd) {
    if (fd >= 0) {
        int enabled = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &enabled, sizeof(enabled));
    }
    return fd;
}

int accept4(int fd, struct sockaddr *addr, socklen_t *len, int flags) {
    int (*next)(int, struct sockaddr *, socklen_t *, int) = dlsym(RTLD_NEXT, "accept4");
    return configure(next(fd, addr, len, flags));
}

int accept(int fd, struct sockaddr *addr, socklen_t *len) {
    int (*next)(int, struct sockaddr *, socklen_t *) = dlsym(RTLD_NEXT, "accept");
    return configure(next(fd, addr, len));
}
