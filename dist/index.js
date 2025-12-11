"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const react_native_1 = require("react-native");
const { HoneywellScanner } = react_native_1.NativeModules;
/**
 * Listen for available events
 * @param  {String} eventName Name of event one of barcodeReadSuccess, barcodeReadFail
 * @param  {Function} handler Event handler
 */
const barcodeReaderEmitter = new react_native_1.NativeEventEmitter(HoneywellScanner);
var subscriptionBarcodeReadSuccess = null;
var subscriptionBarcodeReadFail = null;
var currentSuccessHandler = null; 
var currentFailHandler = null;     

HoneywellScanner.onBarcodeReadSuccess = (handler) => {
    currentSuccessHandler = handler; 
    
    // Solo crear el listener UNA VEZ
    if (!subscriptionBarcodeReadSuccess) {
        subscriptionBarcodeReadSuccess = barcodeReaderEmitter.addListener(
            HoneywellScanner.BARCODE_READ_SUCCESS, 
            (event) => {
                if (currentSuccessHandler) {
                    currentSuccessHandler(event);
                }
            }
        );
    }
};
HoneywellScanner.onBarcodeReadFail = (handler) => {
    subscriptionBarcodeReadFail === null || subscriptionBarcodeReadFail === void 0 ? void 0 : subscriptionBarcodeReadFail.remove();
    subscriptionBarcodeReadFail = null;
    subscriptionBarcodeReadFail = barcodeReaderEmitter.addListener(HoneywellScanner.BARCODE_READ_FAIL, handler);
};
/**
 * Stop listening for event
 * @param  {String} eventName Name of event one of barcodeReadSuccess, barcodeReadFail
 * @param  {Function} handler Event handler
 */
HoneywellScanner.offBarcodeReadSuccess = () => {
    currentSuccessHandler = null;  
    subscriptionBarcodeReadSuccess?.remove();
    subscriptionBarcodeReadSuccess = null; 
};
HoneywellScanner.offBarcodeReadFail = () => {
    subscriptionBarcodeReadFail === null || subscriptionBarcodeReadFail === void 0 ? void 0 : subscriptionBarcodeReadFail.remove();
};
exports.default = HoneywellScanner;
