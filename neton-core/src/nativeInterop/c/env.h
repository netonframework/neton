#ifndef NETON_ENV_H
#define NETON_ENV_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 返回进程环境变量数组 (environ)，Darwin 下由 _NSGetEnviron() 提供。
 * 用于 getEnvMap() 实现，v1.1 冻结。
 */
char **neton_get_environ(void);

#ifdef __cplusplus
}
#endif

#endif /* NETON_ENV_H */
