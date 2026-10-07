#include "lwip/sys.h"
#include <errno.h>
#include <stdlib.h>
#include <time.h>
#include <fcntl.h>
#include <unistd.h>

struct diplay_sem { pthread_mutex_t lock; pthread_cond_t ready; unsigned count; };
struct diplay_mbox { pthread_mutex_t lock; pthread_cond_t read, write; void **items; unsigned size, head, count; };
static pthread_once_t init_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t protect;
static pthread_key_t thread_sem;
static void destroy_thread_sem(void *p) { sys_sem_t *s = p; sys_sem_free(s); free(s); }
static void initialize(void) {
    pthread_mutexattr_t a; pthread_mutexattr_init(&a); pthread_mutexattr_settype(&a, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&protect, &a); pthread_mutexattr_destroy(&a);
    pthread_key_create(&thread_sem, destroy_thread_sem);
}
void sys_init(void) { pthread_once(&init_once, initialize); }
u32_t sys_now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (u32_t)(t.tv_sec * 1000ULL + t.tv_nsec / 1000000); }
u32_t sys_jiffies(void) { return sys_now(); }
static struct timespec deadline(u32_t ms) { struct timespec t; clock_gettime(CLOCK_REALTIME, &t); t.tv_sec += ms / 1000; t.tv_nsec += (ms % 1000) * 1000000L; if (t.tv_nsec >= 1000000000L) { t.tv_sec++; t.tv_nsec -= 1000000000L; } return t; }
static void cond_init(pthread_cond_t *c) { pthread_cond_init(c, NULL); }
err_t sys_sem_new(sys_sem_t *s, u8_t count) { *s = calloc(1, sizeof(**s)); if (!*s) return ERR_MEM; pthread_mutex_init(&(*s)->lock, NULL); cond_init(&(*s)->ready); (*s)->count = count; return ERR_OK; }
void sys_sem_signal(sys_sem_t *s) { pthread_mutex_lock(&(*s)->lock); (*s)->count++; pthread_cond_signal(&(*s)->ready); pthread_mutex_unlock(&(*s)->lock); }
u32_t sys_arch_sem_wait(sys_sem_t *s, u32_t timeout) {
    u32_t start = sys_now(); struct timespec end = deadline(timeout); pthread_mutex_lock(&(*s)->lock);
    while (!(*s)->count) { int r = timeout ? pthread_cond_timedwait(&(*s)->ready, &(*s)->lock, &end) : pthread_cond_wait(&(*s)->ready, &(*s)->lock); if (r == ETIMEDOUT) { pthread_mutex_unlock(&(*s)->lock); return SYS_ARCH_TIMEOUT; } }
    (*s)->count--; pthread_mutex_unlock(&(*s)->lock); return sys_now() - start;
}
void sys_sem_free(sys_sem_t *s) { if (!*s) return; pthread_cond_destroy(&(*s)->ready); pthread_mutex_destroy(&(*s)->lock); free(*s); *s = NULL; }
err_t sys_mutex_new(sys_mutex_t *m) { *m = malloc(sizeof(**m)); if (!*m) return ERR_MEM; pthread_mutex_init(*m, NULL); return ERR_OK; }
void sys_mutex_lock(sys_mutex_t *m) { pthread_mutex_lock(*m); }
void sys_mutex_unlock(sys_mutex_t *m) { pthread_mutex_unlock(*m); }
void sys_mutex_free(sys_mutex_t *m) { pthread_mutex_destroy(*m); free(*m); *m = NULL; }
err_t sys_mbox_new(sys_mbox_t *m, int size) { *m = calloc(1, sizeof(**m)); if (!*m) return ERR_MEM; (*m)->size = size > 0 ? (unsigned)size : 128; (*m)->items = calloc((*m)->size, sizeof(void *)); if (!(*m)->items) { free(*m); *m = NULL; return ERR_MEM; } pthread_mutex_init(&(*m)->lock, NULL); cond_init(&(*m)->read); cond_init(&(*m)->write); return ERR_OK; }
void sys_mbox_post(sys_mbox_t *m, void *msg) { pthread_mutex_lock(&(*m)->lock); while ((*m)->count == (*m)->size) pthread_cond_wait(&(*m)->write, &(*m)->lock); (*m)->items[((*m)->head + (*m)->count++) % (*m)->size] = msg; pthread_cond_signal(&(*m)->read); pthread_mutex_unlock(&(*m)->lock); }
err_t sys_mbox_trypost(sys_mbox_t *m, void *msg) { pthread_mutex_lock(&(*m)->lock); if ((*m)->count == (*m)->size) { pthread_mutex_unlock(&(*m)->lock); return ERR_MEM; } (*m)->items[((*m)->head + (*m)->count++) % (*m)->size] = msg; pthread_cond_signal(&(*m)->read); pthread_mutex_unlock(&(*m)->lock); return ERR_OK; }
err_t sys_mbox_trypost_fromisr(sys_mbox_t *m, void *msg) { return sys_mbox_trypost(m, msg); }
u32_t sys_arch_mbox_fetch(sys_mbox_t *m, void **msg, u32_t timeout) { u32_t start = sys_now(); struct timespec end = deadline(timeout); pthread_mutex_lock(&(*m)->lock); while (!(*m)->count) { int r = timeout ? pthread_cond_timedwait(&(*m)->read, &(*m)->lock, &end) : pthread_cond_wait(&(*m)->read, &(*m)->lock); if (r == ETIMEDOUT) { pthread_mutex_unlock(&(*m)->lock); return SYS_ARCH_TIMEOUT; } } if (msg) *msg = (*m)->items[(*m)->head]; (*m)->head = ((*m)->head + 1) % (*m)->size; (*m)->count--; pthread_cond_signal(&(*m)->write); pthread_mutex_unlock(&(*m)->lock); return sys_now() - start; }
u32_t sys_arch_mbox_tryfetch(sys_mbox_t *m, void **msg) { pthread_mutex_lock(&(*m)->lock); if (!(*m)->count) { pthread_mutex_unlock(&(*m)->lock); return SYS_MBOX_EMPTY; } if (msg) *msg = (*m)->items[(*m)->head]; (*m)->head = ((*m)->head + 1) % (*m)->size; (*m)->count--; pthread_cond_signal(&(*m)->write); pthread_mutex_unlock(&(*m)->lock); return 0; }
void sys_mbox_free(sys_mbox_t *m) { pthread_mutex_destroy(&(*m)->lock); pthread_cond_destroy(&(*m)->read); pthread_cond_destroy(&(*m)->write); free((*m)->items); free(*m); *m = NULL; }
struct thread_start { lwip_thread_fn fn; void *arg; };
static void *run_thread(void *p) { struct thread_start s = *(struct thread_start *)p; free(p); s.fn(s.arg); return NULL; }
sys_thread_t sys_thread_new(const char *name, lwip_thread_fn fn, void *arg, int stacksize, int prio) { (void)name; (void)stacksize; (void)prio; pthread_t t; struct thread_start *s = malloc(sizeof(*s)); LWIP_ASSERT("thread allocation", s); s->fn = fn; s->arg = arg; int r = pthread_create(&t, NULL, run_thread, s); LWIP_ASSERT("thread create", r == 0); pthread_detach(t); return t; }
sys_prot_t sys_arch_protect(void) { sys_init(); pthread_mutex_lock(&protect); return 0; }
void sys_arch_unprotect(sys_prot_t p) { (void)p; pthread_mutex_unlock(&protect); }
void sys_arch_netconn_sem_alloc(void) { sys_init(); if (pthread_getspecific(thread_sem)) return; sys_sem_t *s = malloc(sizeof(*s)); LWIP_ASSERT("thread semaphore", s); err_t r = sys_sem_new(s, 0); LWIP_ASSERT("thread semaphore init", r == ERR_OK); pthread_setspecific(thread_sem, s); }
sys_sem_t *sys_arch_netconn_sem_get(void) { sys_arch_netconn_sem_alloc(); return pthread_getspecific(thread_sem); }
void sys_arch_netconn_sem_free(void) { sys_sem_t *s = pthread_getspecific(thread_sem); if (s) { pthread_setspecific(thread_sem, NULL); destroy_thread_sem(s); } }

/* Android 19 has no getrandom API. Keep TCP/fragment identifiers unpredictable. */
uint32_t diplay_random(void) {
    uint32_t result;
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    LWIP_ASSERT("random source", fd >= 0);
    size_t offset = 0;
    while (offset < sizeof(result)) {
        ssize_t count = read(fd, (unsigned char *)&result + offset, sizeof(result) - offset);
        if (count < 0 && errno == EINTR) continue;
        LWIP_ASSERT("random read", count > 0);
        offset += count;
    }
    close(fd);
    return result;
}
