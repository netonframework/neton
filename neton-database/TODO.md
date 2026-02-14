# Neton-Database 模块 TODO

> **v2 API Freeze**：主路径为 KSP Table + SqlxTableAdapter + sqlx4k SQLite。

## ✅ 已完成（v2）

- [x] Table v2 API 冻结（get/destroy/update/where/query）
- [x] KSP 生成 UserTable（object : Table<User> by SqlxTableAdapter）
- [x] sqlx4k SQLite 适配（adapter/sqlx）
- [x] Query DSL（PredicateScope、orderBy、limit、page）
- [x] DatabaseComponent、config/database.conf
- [x] ensureTable、getOrThrow、NotFoundException → 404
- [x] examples/mvc

## 📋 P1（v2.1）

- [ ] 数据库迁移（Migration 接口、up/down）
- [ ] neton-database 单测

## 📋 P2（v2.2+）

- [x] PostgreSQL/MySQL 支持（SqlxDatabase 按 driver 初始化，ensureTable 方言 DDL）
- [ ] 查询缓存
- [ ] 聚合函数（sum/avg）

## 🗂️ 主路径（用户可见）

```
entity → @Table + @Id
  ↓ KSP
UserTable (object : Table<User> by SqlxTableAdapter)
user.save() / user.delete() / UserTable.where { }.list()
```

