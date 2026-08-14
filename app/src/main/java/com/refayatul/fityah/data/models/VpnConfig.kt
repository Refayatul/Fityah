package com.refayatul.fityah.data.models

enum class DnsType {
    PLAIN, DOH, DOH3
}

data class DnsServer(
    val address: String, // IP for PLAIN, URL for DOH/DOH3
    val type: DnsType = DnsType.PLAIN,
    val isEnabled: Boolean = true
)

data class VpnConfig(
    val isEnabled: Boolean = false,
    val dnsServers: List<DnsServer> = listOf(
        DnsServer("9.9.9.9", DnsType.PLAIN), // Quad9
        DnsServer("https://dns.quad9.net/dns-query", DnsType.DOH3) // Quad9 DoH3
    ),
    val exemptPackages: Set<String> = setOf(
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.refayatul.fityah"
    ),
    val useLocalBlocklist: Boolean = true,
    val forcedSafeSearch: Boolean = false,
    val lockPrivateDns: Boolean = false
)
