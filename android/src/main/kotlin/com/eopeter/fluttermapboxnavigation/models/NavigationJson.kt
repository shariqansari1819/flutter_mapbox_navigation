package com.eopeter.fluttermapboxnavigation.models

data class NavigationJson(
    val directionsResponse: Map<String,Any>,
    val routeOptions: Map<String,Any>,
) {
}