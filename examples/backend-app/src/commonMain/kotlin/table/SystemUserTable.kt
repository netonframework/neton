package table

import model.SystemUser
import model.SystemUserTableImpl
import neton.database.api.Table

object SystemUserTable : Table<SystemUser, Long> by SystemUserTableImpl
