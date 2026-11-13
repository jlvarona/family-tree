package com.moxietech.family_tree.server.collaboration

import kotlinx.serialization.json.Json

/** JSON used only for audit_log before/after snapshots — not the wire-facing Ktor content negotiation instance. */
val AuditJson = Json { encodeDefaults = true }
