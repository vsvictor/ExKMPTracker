package com.exkmptracker.cooperative

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class VehicleClass {
    @SerialName("car") CAR,
    @SerialName("truck") TRUCK,
    @SerialName("bus") BUS,
    @SerialName("trolleybus") TROLLEYBUS,
}
