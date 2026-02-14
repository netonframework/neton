# Neton MVC 示例

MVC 结构示例：users、roles、user_roles 三表 + 聚合 Store。

## 结构

```
mvc/
├── model/           # 实体 + DTO
│   ├── User.kt
│   ├── Role.kt
│   ├── UserRole.kt
│   └── UserWithRoles.kt   # 聚合 DTO
├── controller/      # 控制器
│   ├── UserController.kt
│   ├── RoleController.kt
│   └── UserRoleController.kt
├── store/           # 聚合 Store（跨表联查）
│   └── UserStore.kt
└── Main.kt
```

## 层级

| 层级 | 职责 | 示例 |
|------|------|------|
| **Table** | 单表 CRUD（KSP 生成） | UserTable、RoleTable、UserRoleTable |
| **Store** | 聚合/联查 | UserStore.getWithRoles() |
| **Controller** | HTTP 入口 | UserController、RoleController |

## API

- `GET /api/users` - 用户列表
- `GET /api/users/{id}` - 用户详情
- `GET /api/users/{id}/with-roles` - 用户 + 角色（聚合）
- `POST /api/users` - 创建用户
- `PUT /api/users/{id}` - 更新用户
- `DELETE /api/users/{id}` - 删除用户

- `GET /api/roles` - 角色列表
- `GET /api/roles/{id}` - 角色详情
- `POST /api/roles` - 创建角色
- `PUT /api/roles/{id}` - 更新角色
- `DELETE /api/roles/{id}` - 删除角色

- `GET /api/user-roles` - 用户-角色关联
- `POST /api/user-roles` - 创建关联
- `DELETE /api/user-roles/{id}` - 删除关联

## 运行

```bash
./gradlew :examples:mvc:runDebugExecutableMacosArm64
```
