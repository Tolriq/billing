# Billing Library with developerPayload support

It's a fork of the Play Billing Library: https://developer.android.com/google/play/billing/billing_library.html

Original: com.android.billingclient:billing:1.2.1

Original version lacks proper asynchronous initialisation code:
   
   https://issuetracker.google.com/issues/123117066
   
This fork fixes (until an official release is made):
- Wrong asynchronous code
- Proper timeout and cancellation of asynchronous methods
- Support initialisation of the library in background thread to avoid slow PackageManager calls on main thread.


Please note that official 1.2.1 version should not be used due to tons of blocking calls.