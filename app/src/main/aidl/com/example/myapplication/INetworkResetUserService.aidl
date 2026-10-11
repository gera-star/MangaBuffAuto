package com.example.myapplication;

interface INetworkResetUserService {
    String execute(String command) = 1;
    void destroy() = 16777114;
}
