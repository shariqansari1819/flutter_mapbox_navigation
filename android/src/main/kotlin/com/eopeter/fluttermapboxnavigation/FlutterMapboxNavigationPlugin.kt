package com.eopeter.fluttermapboxnavigation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.eopeter.fluttermapboxnavigation.activity.NavigationLauncher
import com.eopeter.fluttermapboxnavigation.factory.EmbeddedNavigationViewFactory
import com.eopeter.fluttermapboxnavigation.models.MapBoxEvents
import com.eopeter.fluttermapboxnavigation.models.Waypoint
import com.eopeter.fluttermapboxnavigation.models.WaypointSet
import com.eopeter.fluttermapboxnavigation.utilities.PluginUtilities
import com.eopeter.fluttermapboxnavigation.utilities.PluginUtilities.Companion.sendEvent
import com.google.gson.Gson
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.platform.PlatformViewRegistry
import java.io.File

/** FlutterMapboxNavigationPlugin */
class FlutterMapboxNavigationPlugin : FlutterPlugin, MethodCallHandler,
    EventChannel.StreamHandler, ActivityAware {

    private lateinit var channel: MethodChannel
    private lateinit var progressEventChannel: EventChannel
    private var currentActivity: Activity? = null
    private lateinit var currentContext: Context

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val messenger = binding.binaryMessenger
        channel = MethodChannel(messenger, "flutter_mapbox_navigation")
        channel.setMethodCallHandler(this)

        progressEventChannel = EventChannel(messenger, "flutter_mapbox_navigation/events")
        progressEventChannel.setStreamHandler(this)

        platformViewRegistry = binding.platformViewRegistry
        binaryMessenger = messenger


    }

    companion object {

        var eventSink: EventChannel.EventSink? = null

        var PERMISSION_REQUEST_CODE: Int = 367

        lateinit var routes: List<DirectionsRoute>
        private var currentRoute: DirectionsRoute? = null
        val wayPoints: MutableList<Waypoint> = mutableListOf()

        var showAlternateRoutes: Boolean = true
        var longPressDestinationEnabled: Boolean = true
        var allowsUTurnsAtWayPoints: Boolean = false
        var enableOnMapTapCallback: Boolean = false
        var navigationMode = DirectionsCriteria.PROFILE_DRIVING_TRAFFIC
        var simulateRoute = false
        var enableFreeDriveMode = false
        var mapStyleUrlDay: String? = null
        var mapStyleUrlNight: String? = null
        var navigationLanguage = "en"
        var navigationVoiceUnits = DirectionsCriteria.IMPERIAL
        var voiceInstructionsEnabled = true
        var bannerInstructionsEnabled = true
        var zoom = 15.0
        var bearing = 0.0
        var tilt = 0.0
        var distanceRemaining: Float? = null
        var durationRemaining: Double? = null
        var platformViewRegistry: PlatformViewRegistry? = null
        var binaryMessenger: BinaryMessenger? = null

        var viewId = "FlutterMapboxNavigationView"
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${Build.VERSION.RELEASE}")
            }

            "getDistanceRemaining" -> {
                result.success(distanceRemaining)
            }

            "getDurationRemaining" -> {
                result.success(durationRemaining)
            }

            "startFreeDrive" -> {
                enableFreeDriveMode = true
                checkPermissionAndBeginNavigation(call)
            }

            "getRoutePoints" -> {
                getRoutePoints(call, result)
            }

            "startNavigation" -> {
                enableFreeDriveMode = false
                checkPermissionAndBeginNavigation(call)
            }

            "startOffNavigation" -> {
                enableFreeDriveMode = false
                checkOffPermissionAndBeginNavigation(call)
            }

            "addWayPoints" -> {
                addWayPointsToNavigation(call, result)
            }

            "finishNavigation" -> {
                NavigationLauncher.stopNavigation(currentActivity)
            }

            "enableOfflineRouting" -> {
                downloadRegionForOfflineRouting(call, result)
            }

            else -> result.notImplemented()
        }
    }

    private fun downloadRegionForOfflineRouting(
        call: MethodCall,
        result: Result
    ) {
        result.error("TODO", "Not Implemented in Android", "will implement soon")
    }

    private fun checkPermissionAndBeginNavigation(
        call: MethodCall
    ) {
        val arguments = call.arguments as? Map<String, Any>

        val navMode = arguments?.get("mode") as? String
        if (navMode != null) {
            when (navMode) {
                "walking" -> navigationMode = DirectionsCriteria.PROFILE_WALKING
                "cycling" -> navigationMode = DirectionsCriteria.PROFILE_CYCLING
                "driving" -> navigationMode = DirectionsCriteria.PROFILE_DRIVING
            }
        }

        val alternateRoutes = arguments?.get("alternatives") as? Boolean
        if (alternateRoutes != null) {
            showAlternateRoutes = alternateRoutes
        }

        val simulated = arguments?.get("simulateRoute") as? Boolean
        if (simulated != null) {
            simulateRoute = simulated
        }

        val allowsUTurns = arguments?.get("allowsUTurnsAtWayPoints") as? Boolean
        if (allowsUTurns != null) {
            allowsUTurnsAtWayPoints = allowsUTurns
        }

        val onMapTap = arguments?.get("enableOnMapTapCallback") as? Boolean
        if (onMapTap != null) {
            enableOnMapTapCallback = onMapTap
        }

        val language = arguments?.get("language") as? String
        if (language != null) {
            navigationLanguage = language
        }

        val voiceEnabled = arguments?.get("voiceInstructionsEnabled") as? Boolean
        if (voiceEnabled != null) {
            voiceInstructionsEnabled = voiceEnabled
        }

        val bannerEnabled = arguments?.get("bannerInstructionsEnabled") as? Boolean
        if (bannerEnabled != null) {
            bannerInstructionsEnabled = bannerEnabled
        }

        val units = arguments?.get("units") as? String

        if (units != null) {
            if (units == "imperial") {
                navigationVoiceUnits = DirectionsCriteria.IMPERIAL
            } else if (units == "metric") {
                navigationVoiceUnits = DirectionsCriteria.METRIC
            }
        }

        mapStyleUrlDay = arguments?.get("mapStyleUrlDay") as? String
        mapStyleUrlNight = arguments?.get("mapStyleUrlNight") as? String

        val longPress = arguments?.get("longPressDestinationEnabled") as? Boolean
        if (longPress != null) {
            longPressDestinationEnabled = longPress
        }

        wayPoints.clear()

        if (enableFreeDriveMode) {
            checkPermissionAndBeginNavigation(wayPoints)
            return
        }

        val points = arguments?.get("wayPoints") as HashMap<Int, Any>
        for (item in points) {
            val point = item.value as HashMap<*, *>
            val name = point["Name"] as String
            val latitude = point["Latitude"] as Double
            val longitude = point["Longitude"] as Double
            val isSilent = point["IsSilent"] as Boolean
            wayPoints.add(Waypoint(name, longitude, latitude, isSilent))
        }
        checkPermissionAndBeginNavigation(wayPoints)
    }

    private fun checkPermissionAndBeginNavigation(wayPoints: List<Waypoint>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val haspermission =
                currentActivity?.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            if (haspermission != PackageManager.PERMISSION_GRANTED) {
                //_activity.onRequestPermissionsResult((a,b,c) => onRequestPermissionsResult)
                currentActivity?.requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_CODE
                )
                beginNavigation(wayPoints)
            } else
                beginNavigation(wayPoints)
        } else
            beginNavigation(wayPoints)
    }

    private fun beginNavigation(wayPoints: List<Waypoint>) {
        NavigationLauncher.startNavigation(currentActivity, wayPoints)
    }

    private fun checkOffPermissionAndBeginNavigation(
        call: MethodCall
    ) {
        val arguments = call.arguments as? Map<String, Any>

        val navMode = arguments?.get("mode") as? String
        if (navMode != null) {
            when (navMode) {
                "walking" -> navigationMode = DirectionsCriteria.PROFILE_WALKING
                "cycling" -> navigationMode = DirectionsCriteria.PROFILE_CYCLING
                "driving" -> navigationMode = DirectionsCriteria.PROFILE_DRIVING
            }
        }

        val alternateRoutes = arguments?.get("alternatives") as? Boolean
        if (alternateRoutes != null) {
            showAlternateRoutes = alternateRoutes
        }

        val simulated = arguments?.get("simulateRoute") as? Boolean
        if (simulated != null) {
            simulateRoute = simulated
        }

        val allowsUTurns = arguments?.get("allowsUTurnsAtWayPoints") as? Boolean
        if (allowsUTurns != null) {
            allowsUTurnsAtWayPoints = allowsUTurns
        }

        val onMapTap = arguments?.get("enableOnMapTapCallback") as? Boolean
        if (onMapTap != null) {
            enableOnMapTapCallback = onMapTap
        }

        val language = arguments?.get("language") as? String
        if (language != null) {
            navigationLanguage = language
        }

        val voiceEnabled = arguments?.get("voiceInstructionsEnabled") as? Boolean
        if (voiceEnabled != null) {
            voiceInstructionsEnabled = voiceEnabled
        }

        val bannerEnabled = arguments?.get("bannerInstructionsEnabled") as? Boolean
        if (bannerEnabled != null) {
            bannerInstructionsEnabled = bannerEnabled
        }

        val units = arguments?.get("units") as? String

        if (units != null) {
            if (units == "imperial") {
                navigationVoiceUnits = DirectionsCriteria.IMPERIAL
            } else if (units == "metric") {
                navigationVoiceUnits = DirectionsCriteria.METRIC
            }
        }

        mapStyleUrlDay = arguments?.get("mapStyleUrlDay") as? String
        mapStyleUrlNight = arguments?.get("mapStyleUrlNight") as? String

        val longPress = arguments?.get("longPressDestinationEnabled") as? Boolean
        if (longPress != null) {
            longPressDestinationEnabled = longPress
        }

        val points = arguments?.get("routes") as String
//        Log.d("MapboxRoutes", jsonData)
        if (enableFreeDriveMode) {
            checkOffPermissionAndBeginNavigation(points)
            return
        }

        checkOffPermissionAndBeginNavigation(points)
    }

    private fun checkOffPermissionAndBeginNavigation(wayPoints: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val haspermission =
                currentActivity?.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            if (haspermission != PackageManager.PERMISSION_GRANTED) {
                //_activity.onRequestPermissionsResult((a,b,c) => onRequestPermissionsResult)
                currentActivity?.requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_CODE
                )
                beginOffNavigation(wayPoints)
            } else
                beginOffNavigation(wayPoints)
        } else
            beginOffNavigation(wayPoints)
    }

    private fun beginOffNavigation(wayPoints: String) {
        NavigationLauncher.startOffNavigation(currentActivity, wayPoints)
    }


    private fun addWayPointsToNavigation(
        call: MethodCall,
        result: Result
    ) {
        val arguments = call.arguments as? Map<String, Any>
        val points = arguments?.get("wayPoints") as HashMap<Int, Any>

        for (item in points) {
            val point = item.value as HashMap<*, *>
            val name = point["Name"] as String
            val latitude = point["Latitude"] as Double
            val longitude = point["Longitude"] as Double
            val isSilent = point["IsSilent"] as Boolean
            wayPoints.add(Waypoint(name, latitude, longitude, isSilent))
        }
        NavigationLauncher.addWayPoints(currentActivity, wayPoints)
    }

    private fun getRoutePoints(
        call: MethodCall,
        result: Result
    ) {
        val arguments = call.arguments as? Map<String, Any>

        val points = arguments?.get("wayPoints") as HashMap<Int, Any>
        for (item in points) {
            val point = item.value as HashMap<*, *>
            val name = point["Name"] as String
            val latitude = point["Latitude"] as Double
            val longitude = point["Longitude"] as Double
            val isSilent = point["IsSilent"] as Boolean
            wayPoints.add(Waypoint(name, longitude, latitude, isSilent))
        }

        requestRoutes(wayPoints, result)
    }

    private fun requestRoutes(
        waypoint: List<Waypoint>,
        result: Result
    ) {
        var points: MutableList<Waypoint> = mutableListOf()
        if (waypoint != null) points = waypoint.toMutableList()
        val waypointSet: WaypointSet = WaypointSet()
        points.map { waypointSet.add(it) }

        sendEvent(MapBoxEvents.ROUTE_BUILDING)
        var accessToken =
            PluginUtilities.getResourceFromContext(currentContext, "mapbox_access_token")

        val navigationOptions = NavigationOptions.Builder(currentContext)
            .accessToken(accessToken)
            .build()
//        MapboxNavigationApp
//            .setup(navigationOptions)
//            .attach(this)
//
        MapboxNavigationApp.current()
        MapboxNavigationApp.current()!!.requestRoutes(
            routeOptions = RouteOptions
                .builder()
                .applyDefaultNavigationOptions()
                .applyLanguageAndVoiceUnitOptions(currentContext)
                .coordinatesList(waypointSet.coordinatesList())
                .waypointIndicesList(waypointSet.waypointsIndices())
                .waypointNamesList(waypointSet.waypointsNames())
                .language(FlutterMapboxNavigationPlugin.navigationLanguage)
                .alternatives(FlutterMapboxNavigationPlugin.showAlternateRoutes)
                .voiceUnits(FlutterMapboxNavigationPlugin.navigationVoiceUnits)
                .bannerInstructions(FlutterMapboxNavigationPlugin.bannerInstructionsEnabled)
                .voiceInstructions(FlutterMapboxNavigationPlugin.voiceInstructionsEnabled)
                .steps(true)
                .build(),
            callback = object : NavigationRouterCallback {
                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: RouterOrigin) {
                    sendEvent(MapBoxEvents.ROUTE_BUILD_CANCELLED)
                }

                override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                    sendEvent(MapBoxEvents.ROUTE_BUILD_FAILED)
                }

                override fun onRoutesReady(
                    routes: List<NavigationRoute>,
                    routerOrigin: RouterOrigin
                ) {
                    sendEvent(
                        MapBoxEvents.ROUTE_BUILT,
                        Gson().toJson(routes.map { it.directionsRoute.toJson() })
                    )
//                    Log.d("MapboxRoutes", Gson().toJson(routes.map { it.directionsRoute.toJson() }))
                    if (routes.isEmpty()) {
                        sendEvent(MapBoxEvents.ROUTE_BUILD_NO_ROUTES_FOUND)
                        return
                    }

                    val jsonArrayString =
                        Gson().toJson(routes.map { it.directionsResponse.toJson() })
//                    val routeOptionJson = Gson().toJson(routes.map { it.routeOptions.toJson() })

                    result.success(jsonArrayString)
                }
            }
        )


//        MapboxNavigationApp.current()!!.requestRoutes(
//            routeOptions = RouteOptions
//                .builder()
//                .applyDefaultNavigationOptions()
//                .applyLanguageAndVoiceUnitOptions(currentContext)
//                .coordinatesList(waypointSet.coordinatesList())
//                .waypointIndicesList(waypointSet.waypointsIndices())
//                .waypointNamesList(waypointSet.waypointsNames())
//                .language(navigationLanguage)
//                .alternatives(showAlternateRoutes)
//                .voiceUnits(navigationVoiceUnits)
//                .bannerInstructions(bannerInstructionsEnabled)
//                .voiceInstructions(voiceInstructionsEnabled)
//                .steps(true)
//                .build(),
//            callback = object : NavigationRouterCallback {
//                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: RouterOrigin) {
//                    Log.d("MapboxRoutes", "Api was cancelled.")
//                }
//
//                override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
//                    Log.d("MapboxRoutes", reasons.toString())
//                }
//
//                override fun onRoutesReady(
//                    routes: List<NavigationRoute>,
//                    routerOrigin: RouterOrigin
//                ) {
//                    Log.d(
//                        "MapboxRoutes",
//                        Gson().toJson(routes.map { it.directionsRoute.toJson() })
//                    )
//                    result.success(Gson().toJson(routes.map { it.directionsRoute.toJson() }))
//                }
//            }
//        )
    }

    override fun onListen(args: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(args: Any?) {
        eventSink = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        currentActivity = null
        channel.setMethodCallHandler(null)
        progressEventChannel.setStreamHandler(null)
    }

    override fun onDetachedFromActivity() {
        currentActivity!!.finish()
        currentActivity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        currentContext = binding.activity.applicationContext
        if (platformViewRegistry != null && binaryMessenger != null && currentActivity != null) {
            platformViewRegistry?.registerViewFactory(
                viewId,
                EmbeddedNavigationViewFactory(binaryMessenger!!, currentActivity!!)
            )
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        // To change body of created functions use File | Settings | File Templates.
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            367 -> {
                for (permission in permissions) {
                    if (permission == Manifest.permission.ACCESS_FINE_LOCATION) {
                        val haspermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            currentActivity?.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        } else {
                            TODO("VERSION.SDK_INT < M")
                        }
                        if (haspermission == PackageManager.PERMISSION_GRANTED) {
                            if (wayPoints.isNotEmpty())
                                beginNavigation(wayPoints)
                        }
                        // Not all permissions granted. Show some message and return.
                        return
                    }
                }

                // All permissions are granted. Do the work accordingly.
            }
        }
        // super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}

private const val MAPBOX_ACCESS_TOKEN_PLACEHOLDER = "YOUR_MAPBOX_ACCESS_TOKEN_GOES_HERE"