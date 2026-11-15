/*
 * qqluma-hook-shim: LD_PRELOAD helper that loads the SnowLuma native hook
 * into the Linux QQ main process at the right moment.
 *
 * Why this exists: the hook library exports an init entry that must run
 * after QQ's Electron runtime has loaded its "wrapper.node" module;
 * calling it earlier (plain LD_PRELOAD of the hook itself) races the
 * runtime and the hook never engages. This shim stays inert in child
 * processes (any cmdline containing "--type="), watches the main
 * thread's event loop, and once the wait-module shows up in
 * /proc/self/maps it dlopen()s the hook library and calls its entry.
 *
 * Configuration (environment):
 *   QQLUMA_HOOK_LIBRARY   absolute path of the hook .so (required)
 *   QQLUMA_HOOK_SYMBOL    entry symbol (default snowluma_linux_hook_start_dynamic)
 *   QQLUMA_WAIT_MODULE    substring to find in maps (default wrapper.node)
 *   QQLUMA_HOOK_DELAY_MS  minimum age of the process before trying (default 3000)
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

static pid_t main_tid;
static struct timespec born;
static bool attempted;
static bool armed;

static long env_long(const char *key, long fallback) {
    const char *v = getenv(key);
    if (!v || !*v) return fallback;
    char *end = NULL;
    long n = strtol(v, &end, 10);
    return end == v ? fallback : n;
}

static bool module_present(const char *needle) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return false;
    char line[4096];
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, needle)) { found = true; break; }
    }
    fclose(f);
    return found;
}

static void maybe_init(void) {
    if (!armed || attempted) return;
    if (syscall(SYS_gettid) != main_tid) return;

    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    long elapsed_ms = (now.tv_sec - born.tv_sec) * 1000L +
                      (now.tv_nsec - born.tv_nsec) / 1000000L;
    if (elapsed_ms < env_long("QQLUMA_HOOK_DELAY_MS", 3000)) return;

    const char *module = getenv("QQLUMA_WAIT_MODULE");
    if (!module || !*module) module = "wrapper.node";
    if (!module_present(module)) return;

    attempted = true;
    const char *library = getenv("QQLUMA_HOOK_LIBRARY");
    const char *symbol = getenv("QQLUMA_HOOK_SYMBOL");
    if (!symbol || !*symbol) symbol = "snowluma_linux_hook_start_dynamic";
    if (!library || !*library) {
        fprintf(stderr, "[qqluma-hook] QQLUMA_HOOK_LIBRARY not set\n");
        return;
    }
    void *handle = dlopen(library, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        fprintf(stderr, "[qqluma-hook] dlopen failed: %s\n", dlerror());
        return;
    }
    dlerror();
    int (*entry)(void) = (int (*)(void)) dlsym(handle, symbol);
    char *err = dlerror();
    if (err || !entry) {
        fprintf(stderr, "[qqluma-hook] dlsym(%s) failed: %s\n", symbol, err ? err : "?");
        return;
    }
    int rc = entry();
    fprintf(stderr, "[qqluma-hook] hook entry returned %d\n", rc);
    fflush(stderr);
}

int epoll_wait(int fd, struct epoll_event *events, int maxevents, int timeout) {
    static int (*real_fn)(int, struct epoll_event *, int, int) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "epoll_wait");
    maybe_init();
    return real_fn(fd, events, maxevents, timeout);
}

int epoll_pwait(int fd, struct epoll_event *events, int maxevents, int timeout,
                const sigset_t *sigmask) {
    static int (*real_fn)(int, struct epoll_event *, int, int, const sigset_t *) = NULL;
    if (!real_fn) real_fn = dlsym(RTLD_NEXT, "epoll_pwait");
    maybe_init();
    return real_fn(fd, events, maxevents, timeout, sigmask);
}

__attribute__((constructor)) static void shim_init(void) {
    const char *library = getenv("QQLUMA_HOOK_LIBRARY");
    if (!library || !*library) return;

    /* Stay inert in Electron child processes (gpu, renderer, zygote...). */
    char args[8192];
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    ssize_t len = read(fd, args, sizeof(args) - 1);
    close(fd);
    if (len <= 0) return;
    args[len] = '\0';
    for (ssize_t i = 0; i < len; i += (ssize_t)strlen(args + i) + 1) {
        if (strncmp(args + i, "--type=", 7) == 0) return;
    }

    main_tid = (pid_t) syscall(SYS_gettid);
    clock_gettime(CLOCK_MONOTONIC, &born);
    armed = true;
    fprintf(stderr, "[qqluma-hook] armed in pid %d\n", getpid());
    fflush(stderr);
}
