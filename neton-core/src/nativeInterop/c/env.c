#include "env.h"
#include <signal.h>

static volatile sig_atomic_t neton_shutdown_requested = 0;
static void (*neton_previous_sigint_handler)(int) = SIG_DFL;
static void (*neton_previous_sigterm_handler)(int) = SIG_DFL;
static int neton_shutdown_signals_installed = 0;

static void neton_shutdown_signal_handler(int signal_number) {
    (void)signal_number;
    neton_shutdown_requested = 1;
}

void neton_install_shutdown_signals(void) {
    void (*previous_handler)(int);

    neton_shutdown_requested = 0;
    if (neton_shutdown_signals_installed) {
        return;
    }
    previous_handler = signal(SIGINT, neton_shutdown_signal_handler);
    neton_previous_sigint_handler = previous_handler == SIG_ERR ? SIG_DFL : previous_handler;
    previous_handler = signal(SIGTERM, neton_shutdown_signal_handler);
    neton_previous_sigterm_handler = previous_handler == SIG_ERR ? SIG_DFL : previous_handler;
    neton_shutdown_signals_installed = 1;
}

int neton_shutdown_signal_received(void) {
    return neton_shutdown_requested != 0;
}

void neton_reset_shutdown_signals(void) {
    if (neton_shutdown_signals_installed) {
        signal(SIGINT, neton_previous_sigint_handler);
        signal(SIGTERM, neton_previous_sigterm_handler);
        neton_shutdown_signals_installed = 0;
    }
    neton_shutdown_requested = 0;
}

#ifdef __APPLE__
#include <crt_externs.h>

char **neton_get_environ(void) {
    return *_NSGetEnviron();
}
#elif defined(__linux__)
extern char **environ;

char **neton_get_environ(void) {
    return environ;
}
#elif defined(_WIN32) || defined(_WIN64)
#include <stdlib.h>

char **neton_get_environ(void) {
    return _environ;
}
#endif
