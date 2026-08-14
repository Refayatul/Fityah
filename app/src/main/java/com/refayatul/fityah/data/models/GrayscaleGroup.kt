package com.refayatul.fityah.data.models

import java.util.UUID

data class GrayscaleGroup(
val groupId: String = UUID.randomUUID().toString(),
val groupName: String = "",
val packages: HashSet<String> = hashSetOf(),
var timeConfig: AppTimeConfig = AppTimeConfig(),
var isActive: Boolean = true
) {
    override fun toString(): String {
        return "$groupName (${packages.size} apps)"
    }
}
