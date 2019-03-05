# Billing Library with developerPayload support

It's a fork of the Play Billing Library: https://developer.android.com/google/play/billing/billing_library.html

Original: com.android.billingclient:billing:1.2

Original version lacks proper asynchronous initialisation code:
   
   https://issuetracker.google.com/issues/123117066
   
This fork fixes that until an official release is made.

Please note that official 1.2.1 version should not be used due to tons of blocking calls.