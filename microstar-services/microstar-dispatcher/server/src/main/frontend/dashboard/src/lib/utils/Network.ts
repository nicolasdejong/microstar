import {errorStore} from "../stores/ErrorStore";
import {userPasswordStore} from "../stores/UserPasswordStore";
import {userStore} from "../stores/UserStore";
import {starPropertiesStore} from "../stores/StarPropertiesStore";
import {EventReceiver} from "./EventReceiver";
import {isNumber} from "./Utils";
import {get} from 'svelte/store';

let userToken = undefined;
export let userInfo = undefined;
let userInfoPromise = undefined;
const noAutoLogin = !!location.href.match(/.*\Wno(auto)?login/gi);


export type FetchOptions = {
    url: string,
    method?: string,
    body?: any,
    jsonBody?: any, // will be JSON stringified and adds json content header
    headers?: object,
    recursive?: boolean,
    showError?: boolean
};
export type UserInfo = {
    id: string;
    name: string;
    email: string;
    token: string;
    roles: string[];
    raw?: string;
}

export function doFetch(optsIn: string | string[] | FetchOptions, method = 'get', body?: any, headers: object = {}, recursive = false) : Promise<Response> {
    if(Array.isArray(optsIn)) optsIn = ('/' + optsIn.join('/')).replace(/^\/+/, '/');
    if(typeof optsIn != 'object') {
        optsIn = ({ url: optsIn, method: method || 'get', body, headers: headers || {}, recursive, showError: true });
    }
    const opts : FetchOptions = <FetchOptions>optsIn;
    if(!opts.method) opts.method = 'get';
    if(opts.showError === undefined) opts.showError = true;
    if(!opts.headers) opts.headers = {};
    if(opts.jsonBody && !opts.body) {
        opts.body = (typeof opts.jsonBody === 'string' ? opts.jsonBody : JSON.stringify(opts.jsonBody));
        if(!opts.headers['Content-Type']) opts.headers['Content-Type'] = 'application/json';
    }

    return getUserInfo()
        .then(user => { if(!user?.name) throw new Error('userPasswordLogin failed'); })
        .then(_ => { if(location.href.includes('info?')) location.href = '/dashboard'; })
        .then(_ => window.fetch(opts.url, {
            method : opts.method,
            headers: Object.assign({
                        'X-AUTH-TOKEN': userToken,
                        ...(opts.url == '/stars' || !get(starPropertiesStore)?.currentName ? {} : { 'X-STAR-TARGET': get(starPropertiesStore).currentName })
                    }, opts.headers),
            body: opts.body }))
        .then(response => {
            if(response.status === 403/*no token or not allowed*/ || response.status === 401/*token expired*/) {
                if(opts.recursive) throw new Error('Received ' + response.status + ': Action not allowed for ' + opts.url); // when called recursive already, assume not allowed

                // There is an authentication or authorization issue.
                const isNoToken      = !userToken;
                const isTokenExpired = response.status === 401;
                const isNotAllowed   = userToken && response.status == 403;

                if(isNoToken || isTokenExpired) {
                    clearUserData();
                    return doFetch(Object.assign({}, opts, { recursive: true}));
                }
                if(isNotAllowed) {
                    userStore.setIsAdmin(false); // this will hide the tabs
                }
                throw new Error("User is not allowed to " + opts.method + " " + opts.url);
            } else
            if(response.status === 404 && !starPropertiesStore.isOnLocalStar()) { return response; }
            else
            if(response.status === 504 /* gateway timeout -- often when polling for events */) { return response; }
            else
            if(response.status !== 200) return handleErrorResponse(opts, response);
            return response;
        })
        .catch(error => {
            throw error;
        });
}
export function fetchText(url: string | string[] | FetchOptions, method = 'get', body?: string, headers: object = {}) : Promise<string> {
    return doFetch(url, method, body, headers)
        .then(response => response.text());
}
export function fetchData(url: string | string[] | FetchOptions, method = 'get', body?: string, headers: object = {}) : Promise<any> {
    return fetchText(url, method, body, headers)
        // response.json() throws on empty body, but not text() so use JSON.parse() instead
        .then((text) => text ? JSON.parse(text) : {});
}
export function clearUserData() {
    userToken = null;
    userInfo = null;
    userInfoPromise = null;
    delete localStorage.userInfo;
    delete localStorage.testUserInfo;
    clearCookie();
}

/* Returned token may be expired! If that is the case the server will return a 401, triggering a new login */
export function getUserInfo() : Promise<UserInfo> {
    if(userInfo) return Promise.resolve(userInfo);
    return userInfoPromise == null
        ? (userInfoPromise = // NOSONAR on the =  -- Setting tokenPromise so multiple calls to getUserInfo() won't do multiple fetches
            retrieveUserInfo())
            .catch(_ => { userInfoPromise = null; throw new Error('failed'); })
        : userInfoPromise;
}
function retrieveUserInfo() : Promise<UserInfo> {
    return whoAmI()
        .catch(_ => ssoLogin().then(() => (<Promise<UserInfo>>{/*never get here*/})))
        .catch(_ => userPasswordLogin())
        .catch(error => {
            console.log('retrieveUserInfo FAILED!', error);
            throw error;
        });
}
function whoAmI(): Promise<UserInfo> {
    const cookie = getCookie();
    if(!cookie) return Promise.reject('no cookie');
    return window.fetch('/whoami')
        .then(response => {
            if(response.status !== 200) throw new Error('No whoami');
            return response.json().then(info => handleUserInfoText(info.token, info.name, info.id, info.email));
        })
        .catch(error => { clearCookie(); throw(error); })
}
function ssoLogin() {
    if(noAutoLogin) throw new Error('SSO login disabled via ?nologin'); // NO SSO

    // Prevent loops. If SSO fails and redirects back, we'll get back here requesting SSO userPasswordLogin, etc.
    const now = new Date().getTime();
    const llt = localStorage.lastLoginTime ? parseInt(localStorage.lastLoginTime) : 0;
    const ago = now - llt;
    localStorage.lastLoginTime = now;
    if(isNumber(llt) && ago < 5000) throw new Error('Loop detection, not logging in -- refresh to try again');

    // Detect if sso exists by asking for its version and if the succeeds, relocate to it.
    return window.fetch('/sso/version')
        .catch(_ => (<Response>{ status: 0 }))
        .then(response => {
            if(response.status !== 200) throw new Error('No SSO running');
            return response.text().then(body => {
                if(body.length > 100) throw new Error('No SSO running'); // no version returned
                location.href = '/sso/dashboard'; // once logged in, sso will redirect to /dashboard
            })
        });
}
function userPasswordLogin() : Promise<UserInfo> {
    console.log('No SSO -- fallback to username/password');
    const up = userPasswordStore.get();
    const username = up.username;
    const password = up.password;
    if(!username || !password) { userPasswordStore.trigger(); throw 'username and password are mandatory for userPasswordLogin'; }

    function fail(reason: string) {
        userPasswordStore.resetPassword();
        throw new Error(reason);
    }
    function adminBootstrapLogin(): Promise<string> {
        return window.fetch('/admin-login', {method: 'post', headers: {'X-AUTH-PASSWORD': password}})
            .then(resp => {
                if (resp.status !== 200) fail(resp.status === 404 ? 'Bootstrap admin userPasswordLogin disabled' : 'Wrong admin password');
                return resp.text();
            });
    }
    function authLogin(): Promise<string> {
        return window.fetch(`/microstar-authorization/login/${username}`, { method: 'post', headers: { 'X-AUTH-PASSWORD': password } })
            .then(loginResponse => {
                if(loginResponse.status === 404 && username != 'admin') fail('Bootstrap mode: only admin userPasswordLogin allowed');
                if(loginResponse.status != 200) fail("Wrong password");
                return loginResponse.text();
            });
    }
    function toUserInfo(token: string): UserInfo {
        return handleUserInfoText(token, username);
    }

    return adminBootstrapLogin()
        .then(toUserInfo)
        .catch(_ => authLogin().then(handleUserInfoText))
        .catch(error => {
            handleErrorResponse({url:undefined, showError:true}, { text: () => Promise.resolve(<string>error.message), reload: true });
            throw error;
        });
}
function handleUserInfoText(token: string, nameOpt?: string, idOpt?: string, emailOpt?: string): UserInfo {
    userInfo = <UserInfo>{ id:idOpt||'0', name: nameOpt || 'unknown', email: emailOpt || '', token, roles: [] };
    if(!userInfo.token) { userInfo = null; throw new Error("Invalid token"); }
    setCookie('X-AUTH-TOKEN', token);
    userStore.set(userInfo);
    userStore.setIsAdmin(true); // for now -- if a request returns 403 this will be set to false
    userToken = userInfo.token;
    EventReceiver.get().reconnect();
    return userInfo;
}
function handleErrorResponse(opts: FetchOptions, response: any, showErrorDialog = true) {
    return (typeof response.text === 'function' ?  response.text() : Promise.resolve(response['error'] || response)).then(text => {
        console.warn('response', response.status, 'on ', opts.url, 'with error:', text);
        if(!opts.showError) throw text;
        let error = {
            ...(text ? { error: text } : {}),
            status: response.status,
            statusText: response.statusText,
            url: response.url
        }
        try {
            error = JSON.parse(text);
            if (error['timestamp']) error['timestamp'] = new Date(error['timestamp']).toLocaleString();
        } catch(failed) { /* no change */ }

        if(opts.url !== error['path']) error['url'] = Array.isArray(opts.url) ? opts.url.join('/') : opts.url;
        if(showErrorDialog && userInfoPromise && !errorStore.hasError()) { errorStore.reloadOnReset = true; errorStore.setError(error); }
        throw error;
    });
}

function setCookie(name: string, value: string) { document.cookie = name + '=' + value + ';expires=Wed, 01 Jan 2070 00:00:01 GMT'; }
function getCookie(name?: string): string {
    return getCookies()[name || 'X-AUTH-TOKEN'];
}
function getCookies(): object {
    return document.cookie.split(/;\s+/).reduce((a,v) => ({ ...a, [v.split(/=/,2)[0]]: v.split(/=/,2)[1] }), {});
}
function clearCookie(name?: string): void {
    if(name === undefined) name = 'X-AUTH-TOKEN';
    document.cookie = name + '=;expires=Thu, 01 Jan 1970 00:00:00 GMT';
}
