#include "env.h"
#include <signal.h>

#ifdef _WIN32
#include <windows.h>
static volatile LONG neton_shutdown_requested = 0;
static void neton_set_shutdown_requested(int value) {
    InterlockedExchange(&neton_shutdown_requested, value);
}
static BOOL WINAPI neton_console_handler(DWORD event) {
    if (event != CTRL_C_EVENT && event != CTRL_BREAK_EVENT) return FALSE;
    neton_set_shutdown_requested(1);
    return TRUE;
}
#else
static volatile sig_atomic_t neton_shutdown_requested = 0;
static void neton_set_shutdown_requested(int value) {
    neton_shutdown_requested = value;
}
#endif
static void (*neton_previous_sigint_handler)(int) = SIG_DFL;
static void (*neton_previous_sigterm_handler)(int) = SIG_DFL;
static int neton_shutdown_signals_installed = 0;

static void neton_shutdown_signal_handler(int signal_number) {
    (void)signal_number;
    neton_set_shutdown_requested(1);
}

int neton_install_shutdown_signals(void) {
    void (*previous_handler)(int);

    if (neton_shutdown_signals_installed) {
        return 1;
    }
    neton_set_shutdown_requested(0);
    previous_handler = signal(SIGINT, neton_shutdown_signal_handler);
    if (previous_handler == SIG_ERR) return 0;
    neton_previous_sigint_handler = previous_handler;
    previous_handler = signal(SIGTERM, neton_shutdown_signal_handler);
    if (previous_handler == SIG_ERR) {
        signal(SIGINT, neton_previous_sigint_handler);
        return 0;
    }
    neton_previous_sigterm_handler = previous_handler;
#ifdef _WIN32
    if (!SetConsoleCtrlHandler(neton_console_handler, TRUE)) {
        signal(SIGINT, neton_previous_sigint_handler);
        signal(SIGTERM, neton_previous_sigterm_handler);
        return 0;
    }
#endif
    neton_shutdown_signals_installed = 1;
    return 1;
}

int neton_shutdown_signal_received(void) {
#ifdef _WIN32
    return InterlockedCompareExchange(&neton_shutdown_requested, 0, 0) != 0;
#else
    return neton_shutdown_requested != 0;
#endif
}

void neton_reset_shutdown_signals(void) {
    if (neton_shutdown_signals_installed) {
#ifdef _WIN32
        SetConsoleCtrlHandler(neton_console_handler, FALSE);
#endif
        signal(SIGINT, neton_previous_sigint_handler);
        signal(SIGTERM, neton_previous_sigterm_handler);
        neton_shutdown_signals_installed = 0;
    }
    neton_set_shutdown_requested(0);
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
