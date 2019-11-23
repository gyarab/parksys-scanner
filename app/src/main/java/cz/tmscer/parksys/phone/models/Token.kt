package cz.tmscer.parksys.phone.models

enum class TokenEnum {
    REFRESH_TOKEN,
    ACCESS_TOKEN,
}

data class Token(val type: TokenEnum, val value: String)