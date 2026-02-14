#ifdef __APPLE__
#include "env.h"
#include <crt_externs.h>

char **neton_get_environ(void) {
    return *_NSGetEnviron();
}
#elif defined(__linux__)
#include "env.h"

extern char **environ;

char **neton_get_environ(void) {
    return environ;
}
#elif defined(_WIN32) || defined(_WIN64)
#include "env.h"
#include <stdlib.h>

char **neton_get_environ(void) {
    return _environ;
}
#endif
