/**
 * Play Billing Library is licensed to you under the Android Software Development Kit License
 * Agreement - https://developer.android.com/studio/terms ("Agreement"). By using the Play Billing
 * Library, you agree to the terms of this Agreement.
 */
package com.android.billingclient.api;

import static com.android.billingclient.util.BillingHelper.INAPP_CONTINUATION_TOKEN;
import static com.android.billingclient.util.BillingHelper.RESPONSE_BUY_INTENT_KEY;
import static com.android.billingclient.util.BillingHelper.RESPONSE_SUBS_MANAGEMENT_INTENT_KEY;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.UiThread;
import android.support.annotation.VisibleForTesting;
import android.support.annotation.WorkerThread;
import android.text.TextUtils;
import com.android.billingclient.BuildConfig;
import com.android.billingclient.api.Purchase.PurchasesResult;
import com.android.billingclient.api.SkuDetails.SkuDetailsResult;
import com.android.billingclient.util.BillingHelper;
import com.android.vending.billing.IInAppBillingService;
import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONException;

/**
 * Implementation of {@link BillingClient} for communication between the in-app billing library and
 * client's application code.
 */
class BillingClientImpl extends BillingClient {
  private static final String TAG = "BillingClient";

  /** The maximum waiting time in millisecond for Play in-app billing synchronous service call. */
  private static final long FOREGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS = 10_00L;

  /** The maximum waiting time in millisecond for Play in-app billing asynchronous service call. */
  private static final long BACKGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS = 30_000L;

  /**
   * The maximum number of items than can be requested by a call to Billing service's
   * getSkuDetails() method
   */
  private static final int MAX_SKU_DETAILS_ITEMS_PER_REQUEST = 20;

  /** Possible client/billing service relationship states. */
  @IntDef({
    ClientState.DISCONNECTED,
    ClientState.CONNECTING,
    ClientState.CONNECTED,
    ClientState.CLOSED
  })
  @Retention(SOURCE)
  public @interface ClientState {
    /** This client was not yet connected to billing service or was already disconnected from it. */
    int DISCONNECTED = 0;
    /** This client is currently in process of connecting to billing service. */
    int CONNECTING = 1;
    /** This client is currently connected to billing service. */
    int CONNECTED = 2;
    /** This client was already closed and shouldn't be used again. */
    int CLOSED = 3;
  }

  private @ClientState int mClientState = ClientState.DISCONNECTED;

  /** A list of SKUs inside getSkuDetails request bundle. */
  private static final String GET_SKU_DETAILS_ITEM_LIST = "ITEM_ID_LIST";

  /** Version Name of the current library. */
  private static final String LIBRARY_VERSION = BuildConfig.VERSION_NAME;

  /** Maximum IAP version currently supported. */
  private static final int MAX_IAP_VERSION = 8;

  /** Minimum IAP version currently supported. */
  private static final int MIN_IAP_VERSION = 3;

  /** Main (UI) thread handler to post results from Executor. */
  private final Handler mUiThreadHandler = new Handler(Looper.getMainLooper());

  /**
   * Wrapper on top of PURCHASES_UPDATED broadcast receiver to return all purchases receipts to the
   * developer in one place for both app initiated and Play Store initated purhases.
   */
  private final BillingBroadcastManager mBroadcastManager;

  /** Context of the application that initialized this client. */
  private final Context mApplicationContext;

  /** Whether this client is for child directed use. This is mainly used for rewarded skus. */
  @ChildDirected private final int mChildDirected;

  /** Whether this client is for under of age consent use. This is mainly used for rewarded skus. */
  @UnderAgeOfConsent private final int mUnderAgeOfConsent;

  /** Service binder */
  private IInAppBillingService mService;

  /** Connection to the service. */
  private ServiceConnection mServiceConnection;

  /** If subscriptions are is supported (for billing v3 and higher) or not. */
  private boolean mSubscriptionsSupported;

  /** If subscription update is supported (for billing v5 and higher) or not. */
  private boolean mSubscriptionUpdateSupported;

  /**
   * If purchaseHistory and buyIntentExtraParams are supported (for billing v6 and higher) or not.
   */
  private boolean mIABv6Supported;

  /** Indicates if IAB v8 or higher is supported. */
  private boolean mIABv8Supported;

  /**
   * Service that helps us to keep a pool of background threads suitable for current device specs.
   */
  private ExecutorService mExecutorService;

  @VisibleForTesting
  void setExecutorService(ExecutorService executorService) {
    mExecutorService = executorService;
  }

  /** This receiver is triggered by {@link ProxyBillingActivity}. */
  private final ResultReceiver onPurchaseFinishedReceiver =
      new ResultReceiver(mUiThreadHandler) {
        @Override
        public void onReceiveResult(@BillingResponse int resultCode, Bundle resultData) {
          PurchasesUpdatedListener purchasesUpdatedListener = mBroadcastManager.getListener();
          if (purchasesUpdatedListener == null) {
            BillingHelper.logWarn(
                TAG, "PurchasesUpdatedListener is null - no way to return the response.");
            return;
          }
          List<Purchase> purchases = BillingHelper.extractPurchases(resultData);
          purchasesUpdatedListener.onPurchasesUpdated(resultCode, purchases);
        }
      };

  @UiThread
  BillingClientImpl(
      @NonNull Context context,
      @ChildDirected int childDirected,
      @UnderAgeOfConsent int underAgeOfConsent,
      @NonNull PurchasesUpdatedListener listener) {
    mApplicationContext = context.getApplicationContext();
    mChildDirected = childDirected;
    mUnderAgeOfConsent = underAgeOfConsent;
    mBroadcastManager = new BillingBroadcastManager(mApplicationContext, listener);
  }

  @Override
  public @BillingResponse int isFeatureSupported(@FeatureType String feature) {
    if (!isReady()) {
      return BillingResponse.SERVICE_DISCONNECTED;
    }

    switch (feature) {
      case FeatureType.SUBSCRIPTIONS:
        return mSubscriptionsSupported ? BillingResponse.OK : BillingResponse.FEATURE_NOT_SUPPORTED;

      case FeatureType.SUBSCRIPTIONS_UPDATE:
        return mSubscriptionUpdateSupported
            ? BillingResponse.OK
            : BillingResponse.FEATURE_NOT_SUPPORTED;

      case FeatureType.IN_APP_ITEMS_ON_VR:
        return isBillingSupportedOnVr(SkuType.INAPP);

      case FeatureType.SUBSCRIPTIONS_ON_VR:
        return isBillingSupportedOnVr(SkuType.SUBS);

      case FeatureType.PRICE_CHANGE_CONFIRMATION:
        return mIABv8Supported ? BillingResponse.OK : BillingResponse.FEATURE_NOT_SUPPORTED;

      default:
        BillingHelper.logWarn(TAG, "Unsupported feature: " + feature);
        return BillingResponse.DEVELOPER_ERROR;
    }
  }

  @Override
  public boolean isReady() {
    return mClientState == ClientState.CONNECTED && mService != null && mServiceConnection != null;
  }

  @Override
  public void startConnection(@NonNull BillingClientStateListener listener) {
    if (isReady()) {
      BillingHelper.logVerbose(TAG, "Service connection is valid. No need to re-initialize.");
      listener.onBillingSetupFinished(BillingResponse.OK);
      return;
    }

    if (mClientState == ClientState.CONNECTING) {
      BillingHelper.logWarn(
          TAG, "Client is already in the process of connecting to billing service.");
      listener.onBillingSetupFinished(BillingResponse.DEVELOPER_ERROR);
      return;
    }

    if (mClientState == ClientState.CLOSED) {
      BillingHelper.logWarn(
          TAG, "Client was already closed and can't be reused. Please create another instance.");
      listener.onBillingSetupFinished(BillingResponse.DEVELOPER_ERROR);
      return;
    }

    // Switch current state to connecting to avoid race conditions
    mClientState = ClientState.CONNECTING;

    // Start listening for asynchronous purchase results via PURCHASES_UPDATED broadcasts
    mBroadcastManager.registerReceiver();

    // Connection to billing service
    BillingHelper.logVerbose(TAG, "Starting in-app billing setup.");
    mServiceConnection = new BillingServiceConnection(listener);

    Intent serviceIntent = new Intent("com.android.vending.billing.InAppBillingService.BIND");
    serviceIntent.setPackage("com.android.vending");
    List<ResolveInfo> intentServices =
        mApplicationContext.getPackageManager().queryIntentServices(serviceIntent, 0);

    if (intentServices != null && !intentServices.isEmpty()) {
      // Get component info and create ComponentName
      ResolveInfo resolveInfo = intentServices.get(0);
      if (resolveInfo.serviceInfo != null) {
        String packageName = resolveInfo.serviceInfo.packageName;
        String className = resolveInfo.serviceInfo.name;
        if ("com.android.vending".equals(packageName) && className != null) {
          ComponentName component = new ComponentName(packageName, className);
          // Specify component explicitly and don't allow stripping or replacing the package name
          // to avoid exceptions inside 3rd party apps when Play Store was hacked:
          // "IllegalArgumentException: Service Intent must be explicit".
          // See: https://github.com/googlesamples/android-play-billing/issues/62 for more context.
          Intent explicitServiceIntent = new Intent(serviceIntent);
          explicitServiceIntent.setComponent(component);
          explicitServiceIntent.putExtra(BillingHelper.LIBRARY_VERSION_KEY, LIBRARY_VERSION);
          boolean connectionResult =
              mApplicationContext.bindService(
                  explicitServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
          if (connectionResult) {
            // Service connected successfully, listener will be called from mServiceConnection
            BillingHelper.logVerbose(TAG, "Service was bonded successfully.");
            return;
          } else {
            // Service connection was blocked (e.g. this could happen in China), so we are closing
            // the connection and notifying the listener
            BillingHelper.logWarn(TAG, "Connection to Billing service is blocked.");
          }
        } else {
          // Play Store package name is not valid, ending connection
          BillingHelper.logWarn(TAG, "The device doesn't have valid Play Store.");
        }
      }
    }
    // No service available to handle that Intent or service connection was blocked
    mClientState = ClientState.DISCONNECTED;
    BillingHelper.logVerbose(TAG, "Billing service unavailable on device.");
    listener.onBillingSetupFinished(BillingResponse.BILLING_UNAVAILABLE);
  }

  @Override
  public void endConnection() {
    try {
      mBroadcastManager.destroy();
      if (mServiceConnection != null && mService != null) {
        BillingHelper.logVerbose(TAG, "Unbinding from service.");
        mApplicationContext.unbindService(mServiceConnection);
        mServiceConnection = null;
      }
      mService = null;
      if (mExecutorService != null) {
        mExecutorService.shutdownNow();
        mExecutorService = null;
      }
    } catch (Exception ex) {
      BillingHelper.logWarn(TAG, "There was an exception while ending connection: " + ex);
    } finally {
      mClientState = ClientState.CLOSED;
    }
  }

  @Override
  public void launchPriceChangeConfirmationFlow(
      Activity activity,
      PriceChangeFlowParams priceChangeFlowParams,
      @NonNull final PriceChangeConfirmationListener listener) {
    if (!isReady()) {
      listener.onPriceChangeConfirmationResult(BillingResponse.SERVICE_DISCONNECTED);
      return;
    }
    if (priceChangeFlowParams == null || priceChangeFlowParams.getSkuDetails() == null) {
      BillingHelper.logWarn(
          TAG, "Please fix the input params. priceChangeFlowParams must contain valid sku.");
      listener.onPriceChangeConfirmationResult(BillingResponse.DEVELOPER_ERROR);
      return;
    }
    final String sku = priceChangeFlowParams.getSkuDetails().getSku();
    if (sku == null) {
      BillingHelper.logWarn(
          TAG, "Please fix the input params. priceChangeFlowParams must contain valid sku.");
      listener.onPriceChangeConfirmationResult(BillingResponse.DEVELOPER_ERROR);
      return;
    }
    if (!mIABv8Supported) {
      BillingHelper.logWarn(TAG, "Current client doesn't support price change confirmation flow.");
      listener.onPriceChangeConfirmationResult(BillingResponse.FEATURE_NOT_SUPPORTED);
      return;
    }

    Bundle extraParams = new Bundle();
    extraParams.putString(BillingHelper.LIBRARY_VERSION_KEY, LIBRARY_VERSION);
    extraParams.putBoolean(BillingHelper.EXTRA_PARAM_KEY_SUBS_PRICE_CHANGE, true);
    final Bundle extraParamsFinal = extraParams;

    Future<Bundle> futurePriceChangeIntentBundle =
        executeAsync(
            new Callable<Bundle>() {
              @Override
              public Bundle call() throws Exception {
                return mService.getSubscriptionManagementIntent(
                    /* apiVersion= */ 8,
                    mApplicationContext.getPackageName(),
                    sku,
                    SkuType.SUBS,
                    extraParamsFinal);
              }
            }, FOREGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS);

    try {
      Bundle priceChangeIntentBundle =
          futurePriceChangeIntentBundle.get(
              FOREGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS, TimeUnit.MILLISECONDS);

      int responseCode = BillingHelper.getResponseCodeFromBundle(priceChangeIntentBundle, TAG);
      if (responseCode != BillingResponse.OK) {
        BillingHelper.logWarn(
            TAG, "Unable to launch price change flow, error response code: " + responseCode);
        listener.onPriceChangeConfirmationResult(responseCode);
        return;
      }

      final ResultReceiver onPriceChangeConfirmationReceiver =
          new ResultReceiver(mUiThreadHandler) {
            @Override
            public void onReceiveResult(@BillingResponse int resultCode, Bundle resultData) {
              // Receiving the result from local broadcast and triggering a callback on listener.
              listener.onPriceChangeConfirmationResult(resultCode);
            }
          };

      // Launching an invisible activity that will handle the price change flow
      Intent intent = new Intent(activity, ProxyBillingActivity.class);
      PendingIntent pendingIntent =
          priceChangeIntentBundle.getParcelable(RESPONSE_SUBS_MANAGEMENT_INTENT_KEY);
      intent.putExtra(RESPONSE_SUBS_MANAGEMENT_INTENT_KEY, pendingIntent);
      intent.putExtra(ProxyBillingActivity.KEY_RESULT_RECEIVER, onPriceChangeConfirmationReceiver);
      // We need an activity reference here to avoid using FLAG_ACTIVITY_NEW_TASK.
      // But we don't want to keep a reference to it inside the field to avoid memory leaks.
      // Plus all the other methods need just a Context reference, so could be used from the
      // Service or Application.
      activity.startActivity(intent);
    } catch (TimeoutException | CancellationException ex) {
      String msg =
          "Time out while launching Price Change Flow for sku: "
              + sku
              + "; try to reconnect";
      BillingHelper.logWarn(TAG, msg);
      listener.onPriceChangeConfirmationResult(BillingResponse.SERVICE_TIMEOUT);
    } catch (Exception ex) {
      String msg =
          "Exception caught while launching Price Change Flow for sku: "
              + sku
              + "; try to reconnect";
      BillingHelper.logWarn(TAG, msg);
      listener.onPriceChangeConfirmationResult(BillingResponse.SERVICE_DISCONNECTED);
    }
  }

  @Override
  public int launchBillingFlow(Activity activity, final BillingFlowParams params) {
    if (!isReady()) {
      return broadcastFailureAndReturnBillingResponse(BillingResponse.SERVICE_DISCONNECTED);
    }

    final @SkuType String skuType = params.getSkuType();
    final String newSku = params.getSku();
    final SkuDetails skuDetails = params.getSkuDetails();
    final boolean rewardedSku = skuDetails != null && skuDetails.isRewarded();

    // Checking for mandatory params fields
    if (newSku == null) {
      BillingHelper.logWarn(TAG, "Please fix the input params. SKU can't be null.");
      return broadcastFailureAndReturnBillingResponse(BillingResponse.DEVELOPER_ERROR);
    }

    if (skuType == null) {
      BillingHelper.logWarn(TAG, "Please fix the input params. SkuType can't be null.");
      return broadcastFailureAndReturnBillingResponse(BillingResponse.DEVELOPER_ERROR);
    }

    // Checking for requested features support
    if (skuType.equals(SkuType.SUBS) && !mSubscriptionsSupported) {
      BillingHelper.logWarn(TAG, "Current client doesn't support subscriptions.");
      return broadcastFailureAndReturnBillingResponse(BillingResponse.FEATURE_NOT_SUPPORTED);
    }

    boolean isSubscriptionUpdate = (params.getOldSku() != null);

    if (isSubscriptionUpdate && !mSubscriptionUpdateSupported) {
      BillingHelper.logWarn(TAG, "Current client doesn't support subscriptions update.");
      return broadcastFailureAndReturnBillingResponse(BillingResponse.FEATURE_NOT_SUPPORTED);
    }

    if (params.hasExtraParams() && !mIABv6Supported) {
      BillingHelper.logWarn(TAG, "Current client doesn't support extra params for buy intent.");
      return broadcastFailureAndReturnBillingResponse(BillingResponse.FEATURE_NOT_SUPPORTED);
    }

    if (rewardedSku && !mIABv6Supported) {
      BillingHelper.logWarn(TAG, "Current client doesn't support extra params for buy intent.");
      return broadcastFailureAndReturnBillingResponse(BillingResponse.FEATURE_NOT_SUPPORTED);
    }

    BillingHelper.logVerbose(
        TAG, "Constructing buy intent for " + newSku + ", item type: " + skuType);

    Future<Bundle> futureBuyIntentBundle;
    // If IAB v6 is supported, we always try to use buyIntentExtraParams and report the version
    if (mIABv6Supported) {
      Bundle extraParams = constructExtraParams(params);
      extraParams.putString(BillingHelper.LIBRARY_VERSION_KEY, LIBRARY_VERSION);
      if (rewardedSku) {
        extraParams.putString(BillingFlowParams.EXTRA_PARAM_KEY_RSKU, skuDetails.rewardToken());
        if (mChildDirected != ChildDirected.UNSPECIFIED) {
          extraParams.putInt(BillingFlowParams.EXTRA_PARAM_CHILD_DIRECTED, mChildDirected);
        }
        if (mUnderAgeOfConsent != UnderAgeOfConsent.UNSPECIFIED) {
          extraParams.putInt(
              BillingFlowParams.EXTRA_PARAM_UNDER_AGE_OF_CONSENT, mUnderAgeOfConsent);
        }
      }
      final Bundle extraParamsFinal = extraParams;
      final int apiVersion = params.getVrPurchaseFlow() ? 7 : 6;
      futureBuyIntentBundle =
          executeAsync(
              new Callable<Bundle>() {
                @Override
                public Bundle call() throws Exception {
                  return mService.getBuyIntentExtraParams(
                      apiVersion,
                      mApplicationContext.getPackageName(),
                      newSku,
                      skuType,
                      null,
                      extraParamsFinal);
                }
              }, FOREGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS);
    } else if (isSubscriptionUpdate) {
      // For subscriptions update we are calling corresponding service method
      futureBuyIntentBundle =
          executeAsync(
              new Callable<Bundle>() {
                @Override
                public Bundle call() throws Exception {
                  return mService.getBuyIntentToReplaceSkus(
                      /* apiVersion */ 5,
                      mApplicationContext.getPackageName(),
                      Arrays.asList(params.getOldSku()),
                      newSku,
                      SkuType.SUBS,
                      /* developerPayload */ null);
                }
              }, FOREGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS);
    } else {
      futureBuyIntentBundle =
          executeAsync(
              new Callable<Bundle>() {
                @Override
                public Bundle call() throws Exception {
                  return mService.getBuyIntent(
                      /* apiVersion */ 3,
                      mApplicationContext.getPackageName(),
                      newSku,
                      skuType,
                      /* developerPayload */ null);
                }
              }, FOREGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS);
    }
    try {
      Bundle buyIntentBundle =
          futureBuyIntentBundle.get(
              FOREGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS, TimeUnit.MILLISECONDS);
      int responseCode = BillingHelper.getResponseCodeFromBundle(buyIntentBundle, TAG);
      if (responseCode != BillingResponse.OK) {
        BillingHelper.logWarn(TAG, "Unable to buy item, Error response code: " + responseCode);
        return broadcastFailureAndReturnBillingResponse(responseCode);
      }
      // Launching an invisible activity that will handle the purchase result
      Intent intent = new Intent(activity, ProxyBillingActivity.class);
      intent.putExtra(ProxyBillingActivity.KEY_RESULT_RECEIVER, onPurchaseFinishedReceiver);
      PendingIntent pendingIntent = buyIntentBundle.getParcelable(RESPONSE_BUY_INTENT_KEY);
      intent.putExtra(RESPONSE_BUY_INTENT_KEY, pendingIntent);
      // We need an activity reference here to avoid using FLAG_ACTIVITY_NEW_TASK.
      // But we don't want to keep a reference to it inside the field to avoid memory leaks.
      // Plus all the other methods need just a Context reference, so could be used from the
      // Service or Application.
      activity.startActivity(intent);
    } catch (TimeoutException | CancellationException ex) {
      String msg =
          "Time out while launching billing flow: "
              + "; for sku: "
              + newSku
              + "; try to reconnect";
      BillingHelper.logWarn(TAG, msg);
      return broadcastFailureAndReturnBillingResponse(BillingResponse.SERVICE_TIMEOUT);
    } catch (Exception ex) {
        String msg =
            "Exception while launching billing flow: "
                + "; for sku: "
                + newSku
                + "; try to reconnect";
        BillingHelper.logWarn(TAG, msg);
        return broadcastFailureAndReturnBillingResponse(BillingResponse.SERVICE_DISCONNECTED);
    }

    return BillingResponse.OK;
  }

  private int broadcastFailureAndReturnBillingResponse(@BillingResponse int responseCode) {
    mBroadcastManager.getListener().onPurchasesUpdated(responseCode, /* List<Purchase>= */ null);
    return responseCode;
  }

  @Override
  public PurchasesResult queryPurchases(final @SkuType String skuType) {
    if (!isReady()) {
      return new PurchasesResult(BillingResponse.SERVICE_DISCONNECTED, /* purchasesList */ null);
    }

    // Checking for the mandatory argument
    if (TextUtils.isEmpty(skuType)) {
      BillingHelper.logWarn(TAG, "Please provide a valid SKU type.");
      return new PurchasesResult(BillingResponse.DEVELOPER_ERROR, /* purchasesList */ null);
    }

    Future<PurchasesResult> futurePurchaseResult =
        executeAsync(
            new Callable<PurchasesResult>() {
              @Override
              public PurchasesResult call() throws Exception {
                return queryPurchasesInternal(skuType, false /* queryHistory */);
              }
            }, FOREGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS);
    try {
      return futurePurchaseResult.get(
          FOREGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException | CancellationException ex) {
      return new PurchasesResult(BillingResponse.SERVICE_TIMEOUT, /* purchasesList */ null);
    } catch (Exception ex) {
      return new PurchasesResult(BillingResponse.ERROR, /* purchasesList */ null);
    }
  }

  @Override
  public void querySkuDetailsAsync(
      SkuDetailsParams params, final SkuDetailsResponseListener listener) {
    if (!isReady()) {
      listener.onSkuDetailsResponse(
          BillingResponse.SERVICE_DISCONNECTED, /* skuDetailsList */ null);
      return;
    }

    final @SkuType String skuType = params.getSkuType();
    final List<String> skusList = params.getSkusList();

    // Checking for mandatory params fields
    if (TextUtils.isEmpty(skuType)) {
      BillingHelper.logWarn(TAG, "Please fix the input params. SKU type can't be empty.");
      listener.onSkuDetailsResponse(BillingResponse.DEVELOPER_ERROR, /* skuDetailsList */ null);
      return;
    }

    if (skusList == null) {
      BillingHelper.logWarn(TAG, "Please fix the input params. The list of SKUs can't be empty.");
      listener.onSkuDetailsResponse(BillingResponse.DEVELOPER_ERROR, /* skuDetailsList */ null);
      return;
    }

    executeAsync(
        new Runnable() {
          @Override
          public void run() {
            final SkuDetailsResult result = querySkuDetailsInternal(skuType, skusList);
            // Post the result to main thread
            postToUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    listener.onSkuDetailsResponse(result.getResponseCode(), result.getSkuDetailsList());
                  }
                });
          }
        },
        BACKGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS,
        new Runnable() {
          @Override
          public void run() {
            listener.onSkuDetailsResponse(BillingResponse.SERVICE_TIMEOUT, /* skuDetailsList */ null);
          }
        });
  }

  @Override
  public void consumeAsync(final String purchaseToken, final ConsumeResponseListener listener) {
    if (!isReady()) {
      listener.onConsumeResponse(BillingResponse.SERVICE_DISCONNECTED, /* purchaseToken */ null);
      return;
    }

    // Checking for the mandatory argument
    if (TextUtils.isEmpty(purchaseToken)) {
      BillingHelper.logWarn(
          TAG, "Please provide a valid purchase token got from queryPurchases result.");
      listener.onConsumeResponse(BillingResponse.DEVELOPER_ERROR, purchaseToken);
      return;
    }

    executeAsync(
        new Runnable() {
          @Override
          public void run() {
            consumeInternal(purchaseToken, listener);
          }
        },
        BACKGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS,
        new Runnable() {
          @Override
          public void run() {
            listener.onConsumeResponse(BillingResponse.SERVICE_TIMEOUT, purchaseToken);
          }
        });
  }

  @Override
  public void queryPurchaseHistoryAsync(
      final @SkuType String skuType, final PurchaseHistoryResponseListener listener) {
    if (!isReady()) {
      listener.onPurchaseHistoryResponse(
          BillingResponse.SERVICE_DISCONNECTED, /* purchasesList= */ null);
      return;
    }

    executeAsync(
        new Runnable() {
          @Override
          public void run() {
            final PurchasesResult result =
                queryPurchasesInternal(skuType, /* queryHistory= */ true);
            // Post the result to main thread
            postToUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    listener.onPurchaseHistoryResponse(
                        result.getResponseCode(), result.getPurchasesList());
                  }
                });
          }
        },
        BACKGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS,
        new Runnable() {
          @Override
          public void run() {
            listener.onPurchaseHistoryResponse(
                BillingResponse.SERVICE_TIMEOUT, /* purchasesList= */ null);
          }
        });
  }

  @Override
  public void loadRewardedSku(
      final RewardLoadParams params, final RewardResponseListener listener) {

    if (!mIABv6Supported) {
      listener.onRewardResponse(BillingResponse.ITEM_UNAVAILABLE);
      // return unavailable
      return;
    }

    executeAsync(
        new Runnable() {
          @Override
          public void run() {
            Bundle extraParams = new Bundle();
            extraParams.putString(
                BillingFlowParams.EXTRA_PARAM_KEY_RSKU, params.getSkuDetails().rewardToken());
            if (mChildDirected != ChildDirected.UNSPECIFIED) {
              extraParams.putInt(BillingFlowParams.EXTRA_PARAM_CHILD_DIRECTED, mChildDirected);
            }
            if (mUnderAgeOfConsent != UnderAgeOfConsent.UNSPECIFIED) {
              extraParams.putInt(
                  BillingFlowParams.EXTRA_PARAM_UNDER_AGE_OF_CONSENT, mUnderAgeOfConsent);
            }

            Bundle buyIntentBundle;
            try {
              buyIntentBundle =
                  mService.getBuyIntentExtraParams(
                      /* apiVersion= */ 6,
                      mApplicationContext.getPackageName(),
                      params.getSkuDetails().getSku(),
                      params.getSkuDetails().getType(),
                      null,
                      extraParams);
            } catch (final Exception e) {
              postToUiThread(
                  new Runnable() {
                    @Override
                    public void run() {
                      listener.onRewardResponse(BillingResponse.ERROR);
                    }
                  });
              return;
            }

            final int responseCode = BillingHelper.getResponseCodeFromBundle(buyIntentBundle, TAG);

            postToUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    listener.onRewardResponse(responseCode);
                  }
                });
          }
        },
        BACKGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS,
        new Runnable() {
          @Override
          public void run() {
            listener.onRewardResponse(BillingResponse.SERVICE_TIMEOUT);
          }
        });
  }

  private Bundle constructExtraParams(BillingFlowParams params) {
    Bundle extraParams = new Bundle();

    if (params.getReplaceSkusProrationMode()
        != BillingFlowParams.ProrationMode.UNKNOWN_SUBSCRIPTION_UPGRADE_DOWNGRADE_POLICY) {
      extraParams.putInt(
          BillingFlowParams.EXTRA_PARAM_KEY_REPLACE_SKUS_PRORATION_MODE,
          params.getReplaceSkusProrationMode());
    }
    if (params.getAccountId() != null) {
      extraParams.putString(BillingFlowParams.EXTRA_PARAM_KEY_ACCOUNT_ID, params.getAccountId());
    }
    if (params.getVrPurchaseFlow()) {
      extraParams.putBoolean(BillingFlowParams.EXTRA_PARAM_KEY_VR, true);
    }
    if (params.getOldSku() != null) {
      extraParams.putStringArrayList(
          BillingFlowParams.EXTRA_PARAM_KEY_OLD_SKUS,
          new ArrayList<>(Arrays.asList(params.getOldSku())));
    }

    return extraParams;
  }

  private Future<?> executeAsync(Runnable runnable, long maxTimeout, final Runnable onTimeout) {
    long actualTimeout = new Double(0.95 * maxTimeout).longValue();
    if (mExecutorService == null) {
      mExecutorService = Executors.newFixedThreadPool(BillingHelper.NUMBER_OF_CORES);
    }

    final Future<?> task = mExecutorService.submit(runnable);
    mUiThreadHandler.postDelayed(
        new Runnable() {
          @Override
          public void run() {
            if (!task.isDone() && !task.isCancelled()) {
              task.cancel(true);
              BillingHelper.logWarn(TAG, "Async task is taking too long, cancel it!");
              if (onTimeout != null) {
                onTimeout.run();
              }
            }
          }
        },
        actualTimeout);
    return task;
  }

  private <T> Future<T> executeAsync(Callable<T> callable, long maxTimeout) {
    long actualTimeout = new Double(0.95 * maxTimeout).longValue();
    if (mExecutorService == null) {
      mExecutorService = Executors.newFixedThreadPool(BillingHelper.NUMBER_OF_CORES);
    }

    final Future<T> task = mExecutorService.submit(callable);
    mUiThreadHandler.postDelayed(
        new Runnable() {
          @Override
          public void run() {
            if (!task.isDone() && !task.isCancelled()) {
              // Cancel the task, get() method will return CancellationException and it is handled
              // in catch block.
              task.cancel(true);
              BillingHelper.logWarn(TAG, "Async task is taking too long, cancel it!");
            }
          }
        },
        actualTimeout);
    return task;
  }

  /** Checks if billing on VR is supported for corresponding billing type. */
  private int isBillingSupportedOnVr(final @SkuType String skuType) {
    Future<Integer> futureSupportedResult =
        executeAsync(
            new Callable<Integer>() {
              @Override
              public Integer call() throws Exception {
                return mService.isBillingSupportedExtraParams(
                    /* apiVersion= */ 7,
                    mApplicationContext.getPackageName(),
                    skuType,
                    generateVrBundle());
              }
            }, FOREGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS);

    try {
      int supportedResult =
          futureSupportedResult.get(
              FOREGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS, TimeUnit.MILLISECONDS);
      return (supportedResult == BillingResponse.OK)
          ? BillingResponse.OK
          : BillingResponse.FEATURE_NOT_SUPPORTED;
    } catch (Exception e) {
      BillingHelper.logWarn(
          TAG, "Exception while checking if billing is supported; try to reconnect");
      return BillingResponse.SERVICE_DISCONNECTED;
    }
  }

  /**
   * Generates a Bundle to indicate that we are request a method for VR experience within
   * extraParams
   */
  private Bundle generateVrBundle() {
    Bundle result = new Bundle();
    result.putBoolean(BillingFlowParams.EXTRA_PARAM_KEY_VR, true);
    return result;
  }

  @VisibleForTesting
  SkuDetailsResult querySkuDetailsInternal(@SkuType String skuType, List<String> skuList) {
    List<SkuDetails> resultList = new ArrayList<>();

    // Split the sku list into packs of no more than MAX_SKU_DETAILS_ITEMS_PER_REQUEST elements
    int startIndex = 0;
    int listSize = skuList.size();
    while (startIndex < listSize) {
      // Prepare a network request up to a maximum amount of supported elements
      int endIndex = startIndex + MAX_SKU_DETAILS_ITEMS_PER_REQUEST;
      if (endIndex > listSize) {
        endIndex = listSize;
      }
      ArrayList<String> curSkuList = new ArrayList<>(skuList.subList(startIndex, endIndex));
      Bundle querySkus = new Bundle();
      querySkus.putStringArrayList(GET_SKU_DETAILS_ITEM_LIST, curSkuList);
      querySkus.putString(BillingHelper.LIBRARY_VERSION_KEY, LIBRARY_VERSION);
      Bundle skuDetails;
      try {
        skuDetails =
            mService.getSkuDetails(3, mApplicationContext.getPackageName(), skuType, querySkus);
      } catch (Exception e) {
        BillingHelper.logWarn(
            TAG, "Got exception trying to query skuDetails: " + e + "; try to reconnect");
        return new SkuDetailsResult(
            BillingResponse.SERVICE_DISCONNECTED, /* skuDetailsList */ null);
      }

      if (skuDetails == null) {
        BillingHelper.logWarn(TAG, "querySkuDetailsAsync got null sku details list");
        return new SkuDetailsResult(BillingResponse.ITEM_UNAVAILABLE, /* skuDetailsList */ null);
      }

      if (!skuDetails.containsKey(BillingHelper.RESPONSE_GET_SKU_DETAILS_LIST)) {
        @BillingResponse
        int responseCode = BillingHelper.getResponseCodeFromBundle(skuDetails, TAG);

        if (responseCode != BillingResponse.OK) {
          BillingHelper.logWarn(TAG, "getSkuDetails() failed. Response code: " + responseCode);
          return new SkuDetailsResult(responseCode, resultList);
        } else {
          BillingHelper.logWarn(
              TAG, "getSkuDetails() returned a bundle with neither an error nor a detail list.");
          return new SkuDetailsResult(BillingResponse.ERROR, resultList);
        }
      }

      ArrayList<String> skuDetailsJsonList =
          skuDetails.getStringArrayList(BillingHelper.RESPONSE_GET_SKU_DETAILS_LIST);

      if (skuDetailsJsonList == null) {
        BillingHelper.logWarn(TAG, "querySkuDetailsAsync got null response list");
        return new SkuDetailsResult(BillingResponse.ITEM_UNAVAILABLE, /* skuDetailsList */ null);
      }

      for (int i = 0; i < skuDetailsJsonList.size(); ++i) {
        String thisResponse = skuDetailsJsonList.get(i);
        SkuDetails currentSkuDetails;
        try {
          currentSkuDetails = new SkuDetails(thisResponse);
        } catch (JSONException e) {
          BillingHelper.logWarn(TAG, "Got a JSON exception trying to decode SkuDetails");
          return new SkuDetailsResult(BillingResponse.ERROR, /* skuDetailsList */ null);
        }
        BillingHelper.logVerbose(TAG, "Got sku details: " + currentSkuDetails);
        resultList.add(currentSkuDetails);
      }

      // Switching start index to the end of just received pack
      startIndex += MAX_SKU_DETAILS_ITEMS_PER_REQUEST;
    }

    return new SkuDetailsResult(BillingResponse.OK, resultList);
  }

  /**
   * Queries purchases or purchases history and combines all the multi-page results into one list
   */
  private PurchasesResult queryPurchasesInternal(@SkuType String skuType, boolean queryHistory) {
    BillingHelper.logVerbose(
        TAG, "Querying owned items, item type: " + skuType + "; history: " + queryHistory);

    String continueToken = null;
    List<Purchase> resultList = new ArrayList<>();

    do {
      Bundle ownedItems;
      try {
        if (queryHistory) {
          // If current client doesn't support IABv6, then there is no such method yet
          if (!mIABv6Supported) {
            BillingHelper.logWarn(TAG, "getPurchaseHistory is not supported on current device");
            return new PurchasesResult(
                BillingResponse.FEATURE_NOT_SUPPORTED, /* purchasesList */ null);
          }
          ownedItems =
              mService.getPurchaseHistory(
                  /* apiVersion */ 6,
                  mApplicationContext.getPackageName(),
                  skuType,
                  continueToken,
                  /* extraParams */ null);
        } else {
          ownedItems =
              mService.getPurchases(
                  3 /* apiVersion */, mApplicationContext.getPackageName(), skuType, continueToken);
        }
      } catch (Exception e) {
        BillingHelper.logWarn(
            TAG, "Got exception trying to get purchases: " + e + "; try to reconnect");
        return new PurchasesResult(BillingResponse.SERVICE_DISCONNECTED, /* purchasesList */ null);
      }

      if (ownedItems == null) {
        BillingHelper.logWarn(TAG, "queryPurchases got null owned items list");
        return new PurchasesResult(BillingResponse.ERROR, /* purchasesList */ null);
      }

      @BillingResponse int responseCode = BillingHelper.getResponseCodeFromBundle(ownedItems, TAG);

      if (responseCode != BillingResponse.OK) {
        BillingHelper.logWarn(TAG, "getPurchases() failed. Response code: " + responseCode);
        return new PurchasesResult(responseCode, /* purchasesList */ null);
      }

      if (!ownedItems.containsKey(BillingHelper.RESPONSE_INAPP_ITEM_LIST)
          || !ownedItems.containsKey(BillingHelper.RESPONSE_INAPP_PURCHASE_DATA_LIST)
          || !ownedItems.containsKey(BillingHelper.RESPONSE_INAPP_SIGNATURE_LIST)) {
        BillingHelper.logWarn(
            TAG, "Bundle returned from getPurchases() doesn't contain required fields.");
        return new PurchasesResult(BillingResponse.ERROR, /* purchasesList */ null);
      }

      ArrayList<String> ownedSkus =
          ownedItems.getStringArrayList(BillingHelper.RESPONSE_INAPP_ITEM_LIST);
      ArrayList<String> purchaseDataList =
          ownedItems.getStringArrayList(BillingHelper.RESPONSE_INAPP_PURCHASE_DATA_LIST);
      ArrayList<String> signatureList =
          ownedItems.getStringArrayList(BillingHelper.RESPONSE_INAPP_SIGNATURE_LIST);

      if (ownedSkus == null) {
        BillingHelper.logWarn(TAG, "Bundle returned from getPurchases() contains null SKUs list.");
        return new PurchasesResult(BillingResponse.ERROR, /* purchasesList */ null);
      }

      if (purchaseDataList == null) {
        BillingHelper.logWarn(
            TAG, "Bundle returned from getPurchases() contains null purchases list.");
        return new PurchasesResult(BillingResponse.ERROR, /* purchasesList */ null);
      }

      if (signatureList == null) {
        BillingHelper.logWarn(
            TAG, "Bundle returned from getPurchases() contains null signatures list.");
        return new PurchasesResult(BillingResponse.ERROR, /* purchasesList */ null);
      }

      for (int i = 0; i < purchaseDataList.size(); ++i) {
        String purchaseData = purchaseDataList.get(i);
        String signature = signatureList.get(i);
        String sku = ownedSkus.get(i);

        BillingHelper.logVerbose(TAG, "Sku is owned: " + sku);
        Purchase purchase;
        try {
          purchase = new Purchase(purchaseData, signature);
        } catch (JSONException e) {
          BillingHelper.logWarn(TAG, "Got an exception trying to decode the purchase: " + e);
          return new PurchasesResult(BillingResponse.ERROR, /* purchasesList */ null);
        }

        if (TextUtils.isEmpty(purchase.getPurchaseToken())) {
          BillingHelper.logWarn(TAG, "BUG: empty/null token!");
        }

        resultList.add(purchase);
      }

      continueToken = ownedItems.getString(INAPP_CONTINUATION_TOKEN);
      BillingHelper.logVerbose(TAG, "Continuation token: " + continueToken);
    } while (!TextUtils.isEmpty(continueToken));

    return new PurchasesResult(BillingResponse.OK, resultList);
  }

  /** Execute the runnable on the UI/Main Thread */
  private void postToUiThread(Runnable runnable) {
    // Check thread as the task could be interrupted due to timeout and prevent double notification
    if (Thread.interrupted()) {
      return;
    }
    mUiThreadHandler.post(runnable);
  }

  /** Consume the purchase and execute listener's callback on the Ui/Main thread */
  @WorkerThread
  private void consumeInternal(final String purchaseToken, final ConsumeResponseListener listener) {
    try {
      BillingHelper.logVerbose(TAG, "Consuming purchase with token: " + purchaseToken);
      final @BillingResponse int responseCode =
          mService.consumePurchase(
              3 /* apiVersion */, mApplicationContext.getPackageName(), purchaseToken);

      if (responseCode == BillingResponse.OK) {
        postToUiThread(
            new Runnable() {
              @Override
              public void run() {
                BillingHelper.logVerbose(TAG, "Successfully consumed purchase.");
                listener.onConsumeResponse(responseCode, purchaseToken);
              }
            });
      } else {
        postToUiThread(
            new Runnable() {
              @Override
              public void run() {
                BillingHelper.logWarn(
                        TAG, "Error consuming purchase with token. Response code: " + responseCode);
                listener.onConsumeResponse(responseCode, purchaseToken);
              }
            });
      }
    } catch (final Exception e) {
      postToUiThread(
          new Runnable() {
            @Override
            public void run() {
              BillingHelper.logWarn(TAG, "Error consuming purchase; ex: " + e);
              listener.onConsumeResponse(BillingResponse.SERVICE_DISCONNECTED, purchaseToken);
            }
          });
    }
  }

  /** Connect with Billing service and notify listener about important states. */
  private final class BillingServiceConnection implements ServiceConnection {
    private final BillingClientStateListener mListener;

    private BillingServiceConnection(@NonNull BillingClientStateListener listener) {
      mListener = listener;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      BillingHelper.logWarn(TAG, "Billing service disconnected.");
      mService = null;
      mClientState = ClientState.DISCONNECTED;
      mListener.onBillingServiceDisconnected();
    }

    private void notifySetupResult(final int result) {
      postToUiThread(
          new Runnable() {
            @Override
            public void run() {
              mListener.onBillingSetupFinished(result);
            }
          });
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      BillingHelper.logVerbose(TAG, "Billing service connected.");
      mService = IInAppBillingService.Stub.asInterface(service);
      executeAsync(
          new Runnable() {
            @Override
            public void run() {
              int setupResponse = BillingResponse.BILLING_UNAVAILABLE;
              try {
                String packageName = mApplicationContext.getPackageName();
                // Determine the highest supported level for Subs.
                int highestLevelSupportedForSubs = 0;
                for (int apiVersion = MAX_IAP_VERSION;
                    apiVersion >= MIN_IAP_VERSION;
                    apiVersion--) {
                  setupResponse =
                      mService.isBillingSupported(apiVersion, packageName, SkuType.SUBS);
                  if (setupResponse == BillingResponse.OK) {
                    highestLevelSupportedForSubs = apiVersion;
                    break;
                  }
                }
                mSubscriptionUpdateSupported = highestLevelSupportedForSubs >= 5;
                mSubscriptionsSupported = highestLevelSupportedForSubs >= 3;
                if (highestLevelSupportedForSubs < MIN_IAP_VERSION) {
                  BillingHelper.logVerbose(
                      TAG, "In-app billing API does not support subscription on this device.");
                }

                // Determine the highest supported level for InApp.
                int highestLevelSupportedForInApp = 0;
                for (int apiVersion = MAX_IAP_VERSION;
                    apiVersion >= MIN_IAP_VERSION;
                    apiVersion--) {
                  setupResponse =
                      mService.isBillingSupported(apiVersion, packageName, SkuType.INAPP);
                  if (setupResponse == BillingResponse.OK) {
                    highestLevelSupportedForInApp = apiVersion;
                    break;
                  }
                }
                mIABv8Supported = highestLevelSupportedForInApp >= 8;
                mIABv6Supported = highestLevelSupportedForInApp >= 6;
                if (highestLevelSupportedForInApp < MIN_IAP_VERSION) {
                  BillingHelper.logWarn(
                      TAG, "In-app billing API version 3 is not supported on this device.");
                }
                if (setupResponse == BillingResponse.OK) {
                  mClientState = ClientState.CONNECTED;
                } else {
                  mClientState = ClientState.DISCONNECTED;
                  mService = null;
                }
              } catch (Exception e) {
                BillingHelper.logWarn(
                    TAG, "Exception while checking if billing is supported; try to reconnect");
                mClientState = ClientState.DISCONNECTED;
                mService = null;
              }
              notifySetupResult(setupResponse);
            }
          },
          FOREGROUND_FUTURE_TIMEOUT_IN_MILLISECONDS,
          new Runnable() {
            @Override
            public void run() {
              mClientState = ClientState.DISCONNECTED;
              mService = null;
              notifySetupResult(BillingResponse.SERVICE_TIMEOUT);
            }
          });
    }
  }
}
