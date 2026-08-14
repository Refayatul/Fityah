// IFityahApi.aidl
// This is a copy of Fityah's published API contract. It MUST stay byte-for-byte
// compatible with the one inside Fityah (same package, same method order/signatures).
package com.refayatul.fityah.api;

interface IFityahApi {
    int apiVersion();
    boolean isGranted();
    String execute(String command, in Bundle args);
    String query(String state);
    String list(String kind);
}
