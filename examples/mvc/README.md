# Neton MVC 示例

MVC 结构示例：users、roles、user_roles 三表 + Logic 聚合层。

## 结构

```
mvc/
├── model/           # 实体 + DTO
│   ├── User.kt
│   ├── Role.kt
│   ├── UserRole.kt
│   └── UserWithRoles.kt   # 聚合 DTO
├── logic/           # Logic 层（业务聚合 / 跨表联查）
│   ├── UserLogic.kt
│   ├── RoleLogic.kt
│   └── UserRoleLogic.kt
├── controller/      # 控制器
│   ├── UserController.kt
│   ├── RoleController.kt
│   └── UserRoleController.kt
└── Main.kt
```

## 层级

| 层级 | 职责 | 示例 |
|------|------|------|
| **Controller** | HTTP 入口 | UserController、RoleController |
| **Logic** | 业务聚合/联查 | UserLogic.getWithRoles() |
| **Table** | 单表 CRUD（KSP 生成） | UserTable、RoleTable、UserRoleTable |
| **Model** | 实体定义 | User、Role、UserRole |

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
