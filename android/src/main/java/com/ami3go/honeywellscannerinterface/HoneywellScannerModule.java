package com.ami3go.honeywellscannerinterface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.facebook.react.bridge.*;
import com.facebook.react.common.LifecycleState;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.honeywell.aidc.*;
import com.honeywell.aidc.AidcManager.CreatedCallback;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ami3go.honeywellscannerinterface.HoneywellScannerPackage.HoneyWellTAG;

/**
 * Bridge Honeywell AIDC.
 *
 *  - Se crea UN AidcManager/BarcodeReader para toda la vida del modulo (antes se creaba y cerraba uno
 *    por pantalla y por re-render, y un stop durante el create asincronico dejaba un reader "colgado"
 *    con el lector tomado).
 *  - startReader/stopReader solo hacen claim()/release(). release() se hace con un retardo para que
 *    un stop+start consecutivo (cambio de pantalla o re-ejecucion de useFocusEffect) no suelte y vuelva
 *    a tomar el lector.
 *  - En segundo plano se hace release() y al volver claim() (recomendacion de Honeywell).
 *  - Todo el estado se maneja en el hilo principal (donde el SDK entrega onCreated).
 */
public class HoneywellScannerModule extends ReactContextBaseJavaModule
        implements BarcodeReader.BarcodeListener, LifecycleEventListener {

    // Debugging
    private static final boolean D = true;

    private static final long RELEASE_DELAY_MS = 500;

    private static final String BARCODE_READ_SUCCESS = "barcodeReadSuccess";
    private static final String BARCODE_READ_FAIL = "barcodeReadFail";

    private final ReactApplicationContext reactContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Estado: solo se modifica en el hilo principal
    private AidcManager manager;
    private BarcodeReader reader;
    private boolean creating = false;
    private boolean claimed = false;
    private boolean propertiesSet = false;
    private boolean wantsClaim = false;   // hay una pantalla usando el lector
    private boolean resumed;
    private boolean destroyed = false;
    private final List<Promise> pendingStarts = new ArrayList<>();

    // AidcManager.create ignora el resultado de bindService: si el bind falla, onCreated nunca llega.
    private static final long CREATE_TIMEOUT_MS = 5000;
    private final Runnable createTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (creating) {
                Log.e(HoneyWellTAG, "AidcManager.create sin respuesta en " + CREATE_TIMEOUT_MS + "ms (servicio no disponible?)");
                creating = false;
                resolvePendingStarts(false);
            }
        }
    };

    private final Runnable releaseRunnable = new Runnable() {
        @Override
        public void run() {
            if (!wantsClaim) {
                releaseClaim();
            }
        }
    };

    public HoneywellScannerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        this.resumed = reactContext.getLifecycleState() == LifecycleState.RESUMED;
        reactContext.addLifecycleEventListener(this);
    }

    @Override
    public String getName() {
        return "HoneywellScanner";
    }

    /**
     * Send event to javascript
     *
     * @param eventName Name of the event
     * @param params    Additional params
     */
    private void sendEvent(String eventName, @Nullable WritableMap params) {
        if (reactContext.hasActiveCatalystInstance()) {
            if (D) Log.d(HoneyWellTAG, "Sending event: " + eventName);
            reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(eventName, params);
        }
    }

    @Override
    public void onBarcodeEvent(BarcodeReadEvent barcodeReadEvent) {
        if (D) Log.d(HoneyWellTAG, "HoneywellBarcodeReader - Barcode scan read");
        WritableMap params = Arguments.createMap();
        params.putString("data", barcodeReadEvent.getBarcodeData());
        sendEvent(BARCODE_READ_SUCCESS, params);
    }

    @Override
    public void onFailureEvent(BarcodeFailureEvent barcodeFailureEvent) {
        if (D) Log.d(HoneyWellTAG, "HoneywellBarcodeReader - Barcode scan failed");
        sendEvent(BARCODE_READ_FAIL, null);
    }

    /*******************************/
    /** Ciclo de vida             **/
    /*******************************/

    @Override
    public void onHostResume() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                resumed = true;
                if (wantsClaim) {
                    ensureReaderAndClaim();
                }
            }
        });
    }

    @Override
    public void onHostPause() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                resumed = false;
                mainHandler.removeCallbacks(releaseRunnable);
                releaseClaim();
            }
        });
    }

    @Override
    public void onHostDestroy() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                resumed = false;
                mainHandler.removeCallbacks(releaseRunnable);
                closeAll();
            }
        });
    }

    @Override
    public void onCatalystInstanceDestroy() {
        reactContext.removeLifecycleEventListener(this);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                destroyed = true;
                wantsClaim = false;
                mainHandler.removeCallbacks(releaseRunnable);
                closeAll();
            }
        });
    }

    /*******************************/
    /** Methods Available from JS **/
    /*******************************/

    @ReactMethod
    public void startReader(final Promise promise) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                wantsClaim = true;
                mainHandler.removeCallbacks(releaseRunnable);
                if (destroyed) {
                    promise.resolve(false);
                    return;
                }
                if (reader != null) {
                    if (resumed) {
                        claim();
                    }
                    // En segundo plano se resuelve true: se hace claim() al volver (onHostResume).
                    promise.resolve(claimed || !resumed);
                    return;
                }
                pendingStarts.add(promise);
                createManager();
            }
        });
    }

    @ReactMethod
    public void stopReader(final Promise promise) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                wantsClaim = false;
                mainHandler.removeCallbacks(releaseRunnable);
                mainHandler.postDelayed(releaseRunnable, RELEASE_DELAY_MS);
                promise.resolve(null);
            }
        });
    }

    /*******************************/
    /** Logica interna (main)     **/
    /*******************************/

    private void ensureReaderAndClaim() {
        if (reader != null) {
            claim();
        } else {
            createManager();
        }
    }

    private void createManager() {
        if (creating || manager != null) {
            return;
        }
        creating = true;

        // Android 12+ (API 31+) requiere que registerReceiver especifique si el receiver
        // es exportado o no. El SDK de Honeywell AIDC no hace esto, lo que provoca
        // IllegalArgumentException en addBarcodeListener. Usamos un ContextWrapper que
        // inyecta RECEIVER_NOT_EXPORTED automaticamente para solucionar este problema.
        Context contextToUse;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            contextToUse = new ContextWrapper(reactContext) {
                @Override
                public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
                    return super.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                }
            };
        } else {
            contextToUse = reactContext;
        }

        mainHandler.removeCallbacks(createTimeoutRunnable);
        mainHandler.postDelayed(createTimeoutRunnable, CREATE_TIMEOUT_MS);
        try {
            AidcManager.create(contextToUse, new CreatedCallback() {
                @Override
                public void onCreated(final AidcManager aidcManager) {
                    // El SDK lo entrega en el hilo principal; se re-postea por seguridad.
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onManagerCreated(aidcManager);
                        }
                    });
                }
            });
        } catch (Exception e) {
            Log.e(HoneyWellTAG, "AidcManager.create fallo: " + e.getMessage());
            mainHandler.removeCallbacks(createTimeoutRunnable);
            creating = false;
            resolvePendingStarts(false);
        }
    }

    private void onManagerCreated(AidcManager aidcManager) {
        creating = false;
        mainHandler.removeCallbacks(createTimeoutRunnable);
        if (manager != null) {
            // onCreated tardio de un create anterior (despues de un timeout): ya hay uno, se descarta.
            try { aidcManager.close(); } catch (Exception ignored) { }
            return;
        }
        if (destroyed) {
            try { aidcManager.close(); } catch (Exception ignored) { }
            resolvePendingStarts(false);
            return;
        }
        manager = aidcManager;
        try {
            reader = manager.createBarcodeReader();
        } catch (Exception e) {
            Log.e(HoneyWellTAG, "createBarcodeReader fallo: " + e.getMessage());
            reader = null;
        }
        if (reader == null) {
            closeAll();
            resolvePendingStarts(false);
            return;
        }
        try {
            reader.addBarcodeListener(this);
        } catch (IllegalArgumentException e) {
            Log.e(HoneyWellTAG, "addBarcodeListener fallo (posible problema Android 12+): " + e.getMessage());
            closeAll();
            resolvePendingStarts(false);
            return;
        } catch (Exception e) {
            Log.e(HoneyWellTAG, "addBarcodeListener fallo: " + e.getMessage());
            closeAll();
            resolvePendingStarts(false);
            return;
        }
        propertiesSet = false;
        if (wantsClaim && resumed) {
            claim();
            resolvePendingStarts(claimed);
        } else {
            // Se pidio start y luego stop (o la app paso a segundo plano) mientras se creaba.
            resolvePendingStarts(true);
        }
    }

    private void claim() {
        if (reader == null || claimed) {
            return;
        }
        try {
            reader.claim();
            claimed = true;
            if (!propertiesSet) {
                setProperties();
            }
        } catch (ScannerUnavailableException e) {
            Log.e(HoneyWellTAG, "claim(): lector no disponible: " + e.getMessage());
            claimed = false;
        } catch (Exception e) {
            Log.e(HoneyWellTAG, "claim() fallo: " + e.getMessage());
            claimed = false;
        }
    }

    private void setProperties() {
        try {
            reader.setProperty(BarcodeReader.PROPERTY_EAN_8_ENABLED, true);
            reader.setProperty(BarcodeReader.PROPERTY_EAN_8_CHECK_DIGIT_TRANSMIT_ENABLED, true);
            reader.setProperty(BarcodeReader.PROPERTY_EAN_13_ENABLED, true);
            reader.setProperty(BarcodeReader.PROPERTY_EAN_13_CHECK_DIGIT_TRANSMIT_ENABLED, true);
            reader.setProperty(BarcodeReader.PROPERTY_EAN_13_TWO_CHAR_ADDENDA_ENABLED, true);
            reader.setProperty(BarcodeReader.PROPERTY_EAN_13_FIVE_CHAR_ADDENDA_ENABLED, true);
            propertiesSet = true;
        } catch (UnsupportedPropertyException e) {
            Log.e(HoneyWellTAG, "setProperty no soportada: " + e.getMessage());
            propertiesSet = true; // no reintentar en cada claim
        } catch (Exception e) {
            Log.e(HoneyWellTAG, "setProperty fallo: " + e.getMessage());
        }
    }

    private void releaseClaim() {
        if (reader != null && claimed) {
            try {
                reader.release();
            } catch (Exception e) {
                Log.e(HoneyWellTAG, "release() fallo: " + e.getMessage());
            }
        }
        claimed = false;
    }

    private void closeAll() {
        releaseClaim();
        if (reader != null) {
            try { reader.removeBarcodeListener(this); } catch (Exception ignored) { }
            try { reader.close(); } catch (Exception ignored) { }
            reader = null;
        }
        if (manager != null) {
            try { manager.close(); } catch (Exception ignored) { }
            manager = null;
        }
        propertiesSet = false;
    }

    private void resolvePendingStarts(boolean value) {
        List<Promise> copy = new ArrayList<>(pendingStarts);
        pendingStarts.clear();
        for (Promise p : copy) {
            p.resolve(value);
        }
    }

    private boolean isCompatible() {
        // This... is not optimal. Need to find a better way to performantly check whether device has a Honeywell scanner
        return Build.BRAND.toLowerCase().contains("honeywell");
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put("BARCODE_READ_SUCCESS", BARCODE_READ_SUCCESS);
        constants.put("BARCODE_READ_FAIL", BARCODE_READ_FAIL);
        constants.put("isCompatible", isCompatible());
        return constants;
    }

}
