package com.example.core.model

data class CountryCode(
    val iso: String,
    val name: String,
    val dialCode: String,
    val flagEmoji: String,
    val sampleLocalHint: String
)

object CountryCatalog {
    val defaultBangladesh = CountryCode(
        iso = "BD",
        name = "Bangladesh",
        dialCode = "+880",
        flagEmoji = "🇧🇩",
        sampleLocalHint = "1712-345678"
    )

    val all: List<CountryCode> = listOf(
        defaultBangladesh,
        CountryCode("US", "United States", "+1", "🇺🇸", "415-555-0199"),
        CountryCode("GB", "United Kingdom", "+44", "🇬🇧", "7911-123456"),
        CountryCode("IN", "India", "+91", "🇮🇳", "98765-43210"),
        CountryCode("PK", "Pakistan", "+92", "🇵🇰", "300-1234567"),
        CountryCode("SA", "Saudi Arabia", "+966", "🇸🇦", "50-123-4567"),
        CountryCode("AE", "United Arab Emirates", "+971", "🇦🇪", "50-123-4567"),
        CountryCode("MY", "Malaysia", "+60", "🇲🇾", "12-345-6789"),
        CountryCode("SG", "Singapore", "+65", "🇸🇬", "8123-4567"),
        CountryCode("CA", "Canada", "+1", "🇨🇦", "416-555-0142"),
        CountryCode("AU", "Australia", "+61", "🇦🇺", "412-345-678"),
        CountryCode("DE", "Germany", "+49", "🇩🇪", "1512-3456789"),
        CountryCode("FR", "France", "+33", "🇫🇷", "6-12-34-56-78"),
        CountryCode("IT", "Italy", "+39", "🇮🇹", "312-345-6789"),
        CountryCode("ES", "Spain", "+34", "🇪🇸", "612-345-678"),
        CountryCode("JP", "Japan", "+81", "🇯🇵", "90-1234-5678"),
        CountryCode("KR", "South Korea", "+82", "🇰🇷", "10-1234-5678"),
        CountryCode("ID", "Indonesia", "+62", "🇮🇩", "812-345-6789"),
        CountryCode("TR", "Turkey", "+90", "🇹🇷", "532-123-4567"),
        CountryCode("EG", "Egypt", "+20", "🇪🇬", "100-123-4567"),
        CountryCode("QA", "Qatar", "+974", "🇶🇦", "3312-3456"),
        CountryCode("KW", "Kuwait", "+965", "🇰🇼", "5012-3456"),
        CountryCode("OM", "Oman", "+968", "🇴🇲", "9212-3456"),
        CountryCode("BH", "Bahrain", "+973", "🇧🇭", "3600-1234"),
        CountryCode("NP", "Nepal", "+977", "🇳🇵", "984-1234567"),
        CountryCode("LK", "Sri Lanka", "+94", "🇱🇰", "71-234-5678"),
        CountryCode("BR", "Brazil", "+55", "🇧🇷", "11-91234-5678"),
        CountryCode("NG", "Nigeria", "+234", "🇳🇬", "802-123-4567"),
        CountryCode("ZA", "South Africa", "+27", "🇿🇦", "82-123-4567")
    )

    fun formatToE164(country: CountryCode, rawInput: String): String? {
        val stripped = rawInput.replace(Regex("[^0-9+]"), "")
        if (stripped.isBlank()) return null
        if (stripped.startsWith("+")) {
            val digits = stripped.drop(1).filter { it.isDigit() }
            return if (digits.length in 8..15) "+$digits" else null
        }
        var digits = stripped.filter { it.isDigit() }
        // Handle local leading 0 in Bangladesh (+880 017... -> +88017...) and most countries
        if (digits.startsWith("0") && country.dialCode != "+39") {
            digits = digits.dropWhile { it == '0' }
        }
        val dialDigits = country.dialCode.drop(1)
        val fullDigits = if (digits.startsWith(dialDigits) && digits.length >= 11) {
            digits
        } else {
            "$dialDigits$digits"
        }
        // Validate Bangladesh specifically: +880 followed by 10 digits starting with 1 (e.g. +88017XXXXXXXX)
        if (country.dialCode == "+880") {
            val local = fullDigits.removePrefix("880")
            if (local.length != 10 || !local.startsWith("1")) return null
        }
        return if (fullDigits.length in 8..15) "+$fullDigits" else null
    }

    fun normalizeDeviceContactNumber(raw: String, fallbackCountry: CountryCode = defaultBangladesh): String? {
        val cleaned = raw.replace(Regex("[\\s\\-().]"), "")
        if (cleaned.startsWith("+")) {
            val digits = cleaned.drop(1).filter { it.isDigit() }
            return if (digits.length in 8..15) "+$digits" else null
        }
        if (cleaned.startsWith("00")) {
            val digits = cleaned.drop(2).filter { it.isDigit() }
            return if (digits.length in 8..15) "+$digits" else null
        }
        return formatToE164(fallbackCountry, cleaned)
    }
}
