package com.android.placepicker.ui

import android.annotation.SuppressLint
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.support.v4.content.res.ResourcesCompat
import android.support.v4.graphics.drawable.DrawableCompat
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.support.v7.app.AppCompatActivity
import com.android.placepicker.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.single.BasePermissionListener
import com.android.placepicker.PlacePlacePicker
import com.android.placepicker.helper.PermissionsHelper
import com.android.placepicker.inject.DaggerInjector
import com.android.placepicker.viewmodel.PlacePickerViewModel
import com.android.placepicker.viewmodel.Resource
import com.android.placepicker.viewmodel.inject.PlaceViewModelFactory
import kotlinx.android.synthetic.main.activity_place_picker.*
import org.jetbrains.anko.toast
import javax.inject.Inject


class PlacePickerActivity : AppCompatActivity(), OnMapReadyCallback,
        GoogleMap.OnMarkerClickListener,
        PlaceConfirmDialogFragment.OnPlaceConfirmedListener {

    companion object {

        private const val TAG = "Place#PlacePicker"

        // Keys for storing activity state.
        private const val STATE_CAMERA_POSITION = "state_camera_position"
        private const val STATE_LOCATION = "state_location"

        private const val AUTOCOMPLETE_REQUEST_CODE = 1001

        private const val DIALOG_CONFIRM_PLACE_TAG = "dialog_place_confirm"
    }

    private var googleMap: GoogleMap? = null

    private var isLocationPermissionGranted = false

    private var cameraPosition: CameraPosition? = null

    private val defaultLocation = LatLng(37.4219999, -122.0862462)

    private var defaultZoom = -1f

    private var lastKnownLocation: LatLng? = null

    private var placeAdapter: PlacePickerAdapter? = null

    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    private lateinit var viewModel: PlacePickerViewModel

    private var isLoaded: Boolean = false

    @Inject
    lateinit var viewModelFactory: PlaceViewModelFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_place_picker)

        // Inject dependencies
        DaggerInjector.getInjector(application).inject(this)

        // Configure the toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Initialize the ViewModel for this activity
        viewModel = ViewModelProviders.of(this, viewModelFactory)
                .get<PlacePickerViewModel>(PlacePickerViewModel::class.java)

        // Retrieve location and camera position from saved instance state.
        lastKnownLocation = savedInstanceState
                ?.getParcelable(STATE_LOCATION) ?: lastKnownLocation
        cameraPosition = savedInstanceState
                ?.getParcelable(STATE_CAMERA_POSITION) ?: cameraPosition

        // Construct a FusedLocationProviderClient
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        // Sets the default zoom
        defaultZoom = resources.getInteger(R.integer.default_zoom).toFloat()

        // Initialize the UI
        initializeUi()

        // Restore any active fragment
        restoreFragments()

        // Initializes the map
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if ((requestCode == AUTOCOMPLETE_REQUEST_CODE) && (resultCode == RESULT_OK)) {
            data?.run {
                val place = Autocomplete.getPlaceFromIntent(this)
//                showConfirmPlacePopup(place)
                place.latLng?.let {
                    val update = CameraUpdateFactory
                        .newLatLngZoom(it, defaultZoom)
                    googleMap?.moveCamera(update)
                    doUpdateLocation(it)
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_place_picker, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        if (android.R.id.home == item.itemId) {
            finish()
            return true
        }

        if (R.id.action_search == item.itemId) {
            requestPlacesSearch()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    /**
     * Saves the state of the map when the activity is paused.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(STATE_CAMERA_POSITION, googleMap?.cameraPosition)
        outState.putParcelable(STATE_LOCATION, lastKnownLocation)
    }

    override fun onMapReady(map: GoogleMap?) {
        googleMap = map
        map?.setOnMarkerClickListener(this)
        checkForPermission()

        googleMap?.setOnCameraMoveStartedListener {

            dataLayout.visibility = View.GONE
            pbLoading.show()
            tvPlaceName.visibility = View.GONE
            tvPlaceAddress.visibility = View.GONE
        }

        googleMap?.setOnCameraIdleListener {
            val latLng = LatLng(googleMap!!.cameraPosition.target.latitude, googleMap!!.cameraPosition.target.longitude)

            doUpdateLocation(latLng)
        }
    }

    private fun doUpdateLocation(location: LatLng){
        viewModel.getPlaceByLocation(location).observe(this@PlacePickerActivity,
            Observer { updatePlaceByLocation(it!!) })
    }

    private fun updatePlaceByLocation(result: Resource<Place?>) {

        when (result.status) {
            Resource.Status.LOADING -> {
                pbLoading.show()
            }
            Resource.Status.SUCCESS -> {
                dataLayout.visibility = View.VISIBLE
                result.data?.run {

                    tvPlaceName.visibility = View.VISIBLE
                    tvPlaceAddress.visibility = View.VISIBLE
                    tvPlaceName.text = this.name
                    tvPlaceAddress.text = this.address
                }
                pbLoading.hide()
            }
            Resource.Status.ERROR -> {
                toast(R.string.picker_load_this_place_error)
                pbLoading.hide()
            }
        }

    }

    override fun onMarkerClick(marker: Marker): Boolean {

        val place = marker.tag as Place
//        showConfirmPlacePopup(place)
        onPlaceConfirmed(place)

        return false
    }

    override fun onPlaceConfirmed(place: Place) {
        val data = Intent()
        data.putExtra(PlacePlacePicker.EXTRA_PLACE, place)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun bindPlaces(places: List<Place>) {

        // Bind to the recycler view

        if (placeAdapter == null) {
            placeAdapter = PlacePickerAdapter(places) {
//                showConfirmPlacePopup(it)
                onPlaceConfirmed(it)
            }
        }
        else {
            placeAdapter?.swapData(places)
        }

//        rvNearbyPlaces.adapter = placeAdapter

        // Bind to the map

        for (place in places) {

            val marker: Marker? = googleMap?.addMarker(MarkerOptions()
                    .position(place.latLng!!)
                    .icon(getPlaceMarkerBitmap(place)))

            marker?.tag = place
        }
    }

    private fun checkForPermission() {

        PermissionsHelper.checkForLocationPermission(this, object : BasePermissionListener() {

            override fun onPermissionDenied(response: PermissionDeniedResponse?) {
                isLocationPermissionGranted = false
                initMap()
            }

            override fun onPermissionGranted(response: PermissionGrantedResponse?) {
                isLocationPermissionGranted = true
                initMap()
            }
        })

        LocationUtils.displayLocationSettingsRequest(this, object : LocationUtils.LocationListener {
            override fun onLocationChange(location: Location?) {
                if (! isLoaded) {
                    initMap()
                    isLoaded = true
                }
            }

            override fun onLocationError() {
            }

        })
    }

    private fun getDeviceLocation(animate: Boolean) {

        // Get the best and most recent location of the device, which may be null in rare
        // cases when a location is not available.

        try {
            val locationResult = fusedLocationProviderClient.lastLocation
            locationResult
                    ?.addOnFailureListener(this) { setDefaultLocation() }
                    ?.addOnSuccessListener(this) { location: Location? ->

                        // In rare cases location may be null...
                        if (location == null) {
                            Handler().postDelayed({ getDeviceLocation(animate) }, 1000)
                            return@addOnSuccessListener
                        }

                        // Set the map's camera position to the current location of the device.
                        lastKnownLocation = LatLng(location.latitude, location.longitude)

                        val update = CameraUpdateFactory
                                .newLatLngZoom(lastKnownLocation, defaultZoom)

                        if (animate) {
                            googleMap?.animateCamera(update)
                        }
                        else {
                            googleMap?.moveCamera(update)
                        }

                        // Load the places near this location
//                        loadNearbyPlaces()
                    }
        }
        catch (e: SecurityException) {
            Log.e(TAG, e.message)
        }
    }

    @Suppress("DEPRECATION")
    private fun getPlaceMarkerBitmap(place: Place): BitmapDescriptor {

        val innerIconSize: Int = resources.getDimensionPixelSize(R.dimen.marker_inner_icon_size)

        val bgDrawable = ResourcesCompat.getDrawable(resources,
                R.drawable.ic_map_marker_solid_red_32dp, null)!!

        val fgDrawable = ResourcesCompat.getDrawable(resources,
                UiUtils.getPlaceDrawableRes(this, place), null)!!
        DrawableCompat.setTint(fgDrawable, resources.getColor(R.color.colorMarkerInnerIcon))

        val bitmap = Bitmap.createBitmap(bgDrawable.intrinsicWidth,
                bgDrawable.intrinsicHeight, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(bitmap)

        bgDrawable.setBounds(0, 0, canvas.width, canvas.height)

        val left = (canvas.width - innerIconSize) / 2
        val top = (canvas.height - innerIconSize) / 3
        val right = left + innerIconSize
        val bottom = top + innerIconSize

        fgDrawable.setBounds(left, top, right, bottom)

        bgDrawable.draw(canvas)
        fgDrawable.draw(canvas)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun handlePlaceByLocation(result: Resource<Place?>) {

        when (result.status) {
            Resource.Status.LOADING -> {
                pbLoading.show()
            }
            Resource.Status.SUCCESS -> {
                result.data?.run {
//                    showConfirmPlacePopup(this)
                    onPlaceConfirmed(this)
                }
                pbLoading.hide()
            }
            Resource.Status.ERROR -> {
                toast(R.string.picker_load_this_place_error)
                pbLoading.hide()
            }
        }

    }

    private fun handlePlacesLoaded(result: Resource<List<Place>>) {

        when (result.status) {
            Resource.Status.LOADING -> {
                pbLoading.show()
            }
            Resource.Status.SUCCESS -> {
                bindPlaces((result.data ?: listOf()))
                pbLoading.hide()
            }
            Resource.Status.ERROR -> {
                toast(R.string.picker_load_places_error)
                pbLoading.hide()
            }
        }
    }

    private fun initializeUi() {

        // Initialize the recycler view.
//        rvNearbyPlaces.layoutManager = LinearLayoutManager(this)

        // Bind the listeners
        btnMyLocation.setOnClickListener { getDeviceLocation(true) }
        cardSearch.setOnClickListener { requestPlacesSearch() }
        ivMarkerSelect.setOnClickListener { selectThisPlace() }
        tvLocationSelect.setOnClickListener { selectThisPlace() }

        // Hide or show the card search according to the width
        cardSearch.visibility =
                if (resources.getBoolean(R.bool.show_card_search)) View.VISIBLE
                else View.GONE

        // Add a nice fade effect to toolbar
//        appBarLayout.addOnOffsetChangedListener(
//                AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
//                    toolbar.alpha = Math.abs(verticalOffset / appBarLayout.totalScrollRange.toFloat())
//                })

        // Disable vertical scrolling on appBarLayout (it messes with the map...)

//        // Set default behavior
//        val appBarLayoutParams = appBarLayout.layoutParams as CoordinatorLayout.LayoutParams
//        appBarLayoutParams.behavior = AppBarLayout.Behavior()
//
//        // Disable the drag
//        val behavior = appBarLayoutParams.behavior as AppBarLayout.Behavior
//        behavior.setDragCallback(object : AppBarLayout.Behavior.DragCallback() {
//            override fun canDrag(appBarLayout: AppBarLayout): Boolean {
//                return false
//            }
//        })

        // Set the size of AppBarLayout to 68% of the total height
//        val size: Int = (ScreenUtils.getScreenHeight(this) * 68) / 100
//        appBarLayoutParams.height = size
    }

    private fun initMap() {

        // Turn on/off the My Location layer and the related control on the map
        updateLocationUI()

        // Restore any saved state
        restoreMapState()

        if (isLocationPermissionGranted) {

            if (lastKnownLocation == null) {
                // Get the current location of the device and set the position of the map
                getDeviceLocation(false)
            }
            else {
                // Use the last know location to point the map to
                setDefaultLocation()
//                loadNearbyPlaces()
            }
        }
        else {
            setDefaultLocation()
        }
    }

    private fun loadNearbyPlaces() {
        viewModel.getNearbyPlaces(lastKnownLocation ?: defaultLocation)
                .observe(this, Observer { handlePlacesLoaded(it!!) })
    }

    private fun requestPlacesSearch() {

        // These fields are not charged by Google:
        // https://developers.google.com/places/android-sdk/usage-and-billing#basic-data
        val placeFields = listOf(
                Place.Field.ID,
                Place.Field.NAME,
                Place.Field.ADDRESS,
                Place.Field.LAT_LNG,
                Place.Field.TYPES,
                Place.Field.PHOTO_METADATAS)

        // Start the autocomplete intent.
        val intent = Autocomplete.IntentBuilder(AutocompleteActivityMode.FULLSCREEN, placeFields)
                .build(this)

        startActivityForResult(intent, AUTOCOMPLETE_REQUEST_CODE)
    }

    private fun restoreFragments() {
        val confirmFragment = supportFragmentManager
                .findFragmentByTag(DIALOG_CONFIRM_PLACE_TAG) as PlaceConfirmDialogFragment?
        confirmFragment?.run {
            confirmListener = this@PlacePickerActivity
        }
    }

    private fun restoreMapState() {
        cameraPosition?.run {
            googleMap?.moveCamera(CameraUpdateFactory.newCameraPosition(this))
        }
    }

    private fun selectThisPlace() {
        googleMap?.cameraPosition?.run {
            viewModel.getPlaceByLocation(this.target).observe(this@PlacePickerActivity,
                    Observer { handlePlaceByLocation(it!!) })
        }
    }

    private fun setDefaultLocation() {
        val default: LatLng = lastKnownLocation ?: defaultLocation
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(default, defaultZoom))
    }

    private fun showConfirmPlacePopup(place: Place) {
        val fragment = PlaceConfirmDialogFragment.newInstance(place, this)
        fragment.show(supportFragmentManager, DIALOG_CONFIRM_PLACE_TAG)
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationUI() {

        googleMap?.uiSettings?.isMyLocationButtonEnabled = false

        if (isLocationPermissionGranted) {
            googleMap?.isMyLocationEnabled = true
            btnMyLocation.visibility = View.VISIBLE
        }
        else {
            btnMyLocation.visibility = View.GONE
            googleMap?.isMyLocationEnabled = false
        }
    }
}
