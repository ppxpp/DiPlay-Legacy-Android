#ifndef DIPLAY_SYS_ARCH_H
#define DIPLAY_SYS_ARCH_H
#include <pthread.h>
#include <stdint.h>
struct diplay_sem;
struct diplay_mbox;
typedef struct diplay_sem *sys_sem_t;
typedef struct diplay_mbox *sys_mbox_t;
typedef pthread_mutex_t *sys_mutex_t;
typedef pthread_t sys_thread_t;
typedef unsigned int sys_prot_t;
#define SYS_SEM_NULL NULL
#define SYS_MBOX_NULL NULL
#define sys_sem_valid(s) (*(s) != NULL)
#define sys_sem_set_invalid(s) (*(s) = NULL)
#define sys_mbox_valid(m) (*(m) != NULL)
#define sys_mbox_set_invalid(m) (*(m) = NULL)
#define sys_mutex_valid(m) (*(m) != NULL)
#define sys_mutex_set_invalid(m) (*(m) = NULL)
sys_sem_t *sys_arch_netconn_sem_get(void);
void sys_arch_netconn_sem_alloc(void);
void sys_arch_netconn_sem_free(void);
#define LWIP_NETCONN_THREAD_SEM_GET() sys_arch_netconn_sem_get()
#define LWIP_NETCONN_THREAD_SEM_ALLOC() sys_arch_netconn_sem_alloc()
#define LWIP_NETCONN_THREAD_SEM_FREE() sys_arch_netconn_sem_free()
#endif
