package com.vincentmasselis.rxbluetoothkotlin

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.O_MR1
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.vincentmasselis.rxbluetoothkotlin.internal.ContextHolder
import com.vincentmasselis.rxbluetoothkotlin.internal.toObservable
import io.reactivex.*
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import no.nordicsemi.android.support.v18.scanner.*
import java.util.concurrent.TimeUnit

private const val TAG = "BluetoothManager+rx"
/**
 * Reactive way to get [ScanResult] while scanning.
 *
 * @param scanArgs If [scanArgs] param is not null, the method [android.bluetooth.le.BluetoothLeScanner.startScan] with 3 params will be called instead of the one with 1 param.
 *
 * @param flushEvery If [flushEvery] is not null, [android.bluetooth.le.BluetoothLeScanner.flushPendingScanResults] will be called repeatedly with the specified delay until the
 * downstream is disposed.
 *
 * Warning ! It never completes ! It stops his scan only when the downstream is disposed.
 * For example you can use a [Flowable.takeUntil] + [Flowable.timer] operator to stop scanning after a delay.
 * Alternatively you can use an [Flowable.firstElement] if you have set a [scanArgs] or you can simply call [io.reactivex.disposables.Disposable.dispose] when your job is done.
 *
 * @return
 * onNext with [ScanResult]
 *
 * onComplete is never called. The downstream has to dispose to stop the scan.
 *
 * onError if an error has occurred. It can emit [DeviceDoesNotSupportBluetooth], [NeedLocationPermission], [BluetoothIsTurnedOff], [LocationServiceDisabled] and
 * [ScanFailedException]
 *
 * @see android.bluetooth.le.ScanResult
 * @see android.bluetooth.le.ScanFilter
 * @see android.bluetooth.le.ScanSettings
 * @see [android.bluetooth.le.BluetoothLeScanner.startScan]
 * @see [android.bluetooth.le.BluetoothLeScanner.flushPendingScanResults]
 */
fun BluetoothManager.rxScan(
    scanArgs: Pair<List<ScanFilter>, ScanSettings>? = null,
    flushEvery: Pair<Long, TimeUnit>? = null,
    logger: Logger? = null
): Flowable<ScanResult> =
    Completable
        .defer {
            when {
                adapter == null || ContextHolder.context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE).not() -> {
                    logger?.v(TAG, "rxScan(), error : DeviceDoesNotSupportBluetooth()")
                    return@defer Completable.error(DeviceDoesNotSupportBluetooth())
                }
                ContextCompat.checkSelfPermission(ContextHolder.context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(ContextHolder.context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> {
                    logger?.v(TAG, "rxScan(), error : NeedLocationPermission()")
                    return@defer Completable.error(NeedLocationPermission())
                }
                adapter.isEnabled.not() -> {
                    logger?.v(TAG, "rxScan(), error : BluetoothIsTurnedOff()")
                    return@defer Completable.error(BluetoothIsTurnedOff())
                }
                SDK_INT >= Build.VERSION_CODES.M &&
                        (ContextHolder.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).let { locationManager ->
                            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER).not() && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER).not()
                        } -> {
                    logger?.v(TAG, "rxScan(), error : LocationServiceDisabled()")
                    return@defer Completable.error(LocationServiceDisabled())
                }
            }

            Completable.complete()
        }
        .andThen(
            Flowable.create<ScanResult>({ downStream ->

                // Used to prevent memory leaks
                var safeDownStream = downStream as FlowableEmitter<ScanResult>?

                val callback = object : ScanCallback() {

                    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                    override fun onScanResult(callbackType: Int, result: ScanResult) {//TODO Handle callbackType
                        safeDownStream?.onNext(result)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        val error = ScanFailedException(errorCode)
                        logger?.v(TAG, "rxScan(), error ScanFailedException : $error")
                        safeDownStream?.tryOnError(error)
                    }
                }

                val disposables = CompositeDisposable()

                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                    .toObservable(ContextHolder.context)
                    .subscribe { (_, intent) ->
                        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> downStream.tryOnError(BluetoothIsTurnedOff())
                            else -> {
                            }
                        }
                    }
                    .let { disposables.add(it) }

                val scanner = BluetoothLeScannerCompat.getScanner()

                flushEvery?.run {
                    Observable
                        .interval(flushEvery.first, flushEvery.second, AndroidSchedulers.mainThread())
                        .subscribe { scanner.flushPendingScanResults(callback) }
                        .let { disposables.add(it) }
                }

                if (scanArgs != null) {
                    logger?.v(TAG, "rxScan(), startScan() with scanArgs.first : ${scanArgs.first} and scanArgs.second : ${scanArgs.second}")
                    scanner.startScan(scanArgs.first, scanArgs.second, callback)
                } else {
                    logger?.v(TAG, "rxScan(), startScan() without scanArgs")
                    scanner.startScan(callback)
                }

                @SuppressLint("NewApi")
                if (SDK_INT >= O_MR1) {
                    Single
                        .fromCallable {
                            //Since I'm using a scanner compat from nordic, the callback that the system hold is not mine but an instance crated by the nordic lib.
                            val realCallback = scanner.javaClass.superclass?.getDeclaredField("mCallbacks")
                                ?.apply { isAccessible = true }
                                ?.get(scanner)
                                ?.let { it as? Map<*, *> }
                                ?.get(callback)
                            val systemScanner = adapter.bluetoothLeScanner
                            systemScanner.javaClass.getDeclaredField("mLeScanClients")
                                .apply { isAccessible = true }
                                .get(systemScanner)
                                .let { it as? Map<*, *> }
                                ?.get(realCallback)
                                ?.let { bluetoothLeScanner ->
                                    bluetoothLeScanner.javaClass.getDeclaredField("mScannerId")
                                        .apply { isAccessible = true }
                                        .get(bluetoothLeScanner)
                                }
                                ?.run { this as? Int }
                                ?.let { mScannerId ->
                                    return@fromCallable mScannerId
                                }
                        }
                        .subscribeOn(Schedulers.computation())
                        .subscribe({ mScannerId ->
                            logger?.v(TAG, "rxScan(), system mScannerId for this scan : $mScannerId")
                            if (mScannerId == -2) //Value fetched from BluetoothLeScanner$BleScanCallbackWrapper.mScannerId. If you check the API27 sources, you will see a -2 in this field when the exception SCAN_FAILED_SCANNING_TOO_FREQUENTLY is fired.
                                downStream.tryOnError(ScanFailedException(6))
                        }, {
                            logger?.w(
                                TAG,
                                "rxScan() is unable to compute system's mScannerId for this scan, it has no effect on the execution of rxScan() but it can leads to bugs from the Android SDK API 27. More information here : https://issuetracker.google.com/issues/71736547",
                                it
                            )
                        })
                        .let { disposables.add(it) }
                }

                downStream.setCancellable {
                    safeDownStream = null
                    disposables.dispose()
                    Handler(Looper.getMainLooper()).post {
                        logger?.v(TAG, "rxScan(), stopScan()")
                        try {
                            scanner.stopScan(callback)
                        } catch (e: IllegalStateException) {
                            // IllegalStateException is fired is stop scan is called while the bluetooth is already turned off
                        }
                    }
                }
            }, BackpressureStrategy.BUFFER)
        )
        .subscribeOn(AndroidSchedulers.mainThread())