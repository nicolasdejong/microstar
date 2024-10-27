import type {ProcessInfo, ServiceId} from "../types/Services";
import {stringToServiceId} from "../types/Services";
import {fetchText, getUserInfo} from "./Network";
import {concatPath} from "./Utils";

export interface LogEvent {
    log: string;
}
export interface RegisterEvent {
    id: ServiceId;
    instanceId: string;
}
export interface UnregisterEvent {
    id: ServiceId;
    instanceId: string;
}
export interface JarEvent {
    name: string;
}
export interface DataStoreEvent {
    name: string; // name of datastore
    path: string; // path within datastore that changed
}
export interface ProgressEvent {
    id : string;
    countDone : number;
    count : number;
    sizeDone : number;
    size : number;
    message : string;
    error : string;
}
interface EmittedEvent {
    type : string;
    data : object | LogEvent;
}
export class EventReceiver {
    private webSocket: WebSocket;
    private instanceId?: string;
    private handlers: Map<string, ((arg : object) => void)[]> = new Map();
    private reconnectingTimerId : number;
    private lastPollEventTime = 0;
    private polling = false;
    private connecting = false;
    private pingRepeater = null;
    private static defaultEventReceiver : EventReceiver;

    static get() : EventReceiver {
        if(!this.defaultEventReceiver) this.defaultEventReceiver = this.unconnected();
        return this.defaultEventReceiver;
    }
    static unconnected() : EventReceiver {
        return new EventReceiver();
    }
    static connect(instanceId? : string) : EventReceiver {
        return EventReceiver.get().connect(instanceId);
    }


    reconnect() : this {
        this.disconnect();
        this.connect(this.instanceId);
        return this;
    }
    disconnect() : this {
        this.close();
        return this;
    }
    connect(instanceId? : string) : this {
        this.close();
        this.connectTo(instanceId);
        return this;
    }
    close() : void {
        const ws = this.webSocket;
        this.webSocket = null;
        if(this.reconnectingTimerId) { clearInterval(this.reconnectingTimerId); this.reconnectingTimerId = null; }
        if(ws) ws.close();
    }
    isConnected() : boolean { return this.webSocket != null; }
    onLog                  (callback: (evt : LogEvent)        => void)            : this { return this.onEvent('LOG',          callback); }
    onRegistered           (callback: (evt : RegisterEvent)   => void)            : this { return this.onEvent('REGISTERED',   callback); }
    onUnregistered         (callback: (evt : UnregisterEvent) => void)            : this { return this.onEvent('UNREGISTERED', callback); }
    onStarting             (callback: (evt : UnregisterEvent) => void)            : this { return this.onEvent('SERVICE-STARTING', callback); }
    onNewFrontendSettings  (callback: () => void)                                 : this { return this.onEvent('SETTINGS-FRONTEND-CHANGED', callback); }
    onNewDispatcherSettings(callback: () => void)                                 : this { return this.onEvent('SETTINGS-DISPATCHER-CHANGED', callback); }
    onStarsChanged         (callback: () => void)                                 : this { return this.onEvent('STARS-CHANGED', callback); }
    onAddedJar             (callback: (evt : JarEvent) => void)                   : this { return this.onEvent('ADDED-JAR', callback); }
    onRemovedJar           (callback: (evt : JarEvent) => void)                   : this { return this.onEvent('REMOVED-JAR', callback); }
    onStaticDataChanged    (callback: () => void)                                 : this { return this.onEvent('STATIC-DATA-CHANGED', callback); }
    onDataStoresChanged    (callback: () => void)                                 : this { return this.onEvent('DATA-STORES-CHANGED', callback); }
    onDataStoreChanged     (callback: (evt : DataStoreEvent) => void)             : this { return this.onEvent('DATA-STORE-CHANGED', callback); }
    onDataStoreProgress    (callback: (evt : ProgressEvent) => void)              : this { return this.onEvent('DATA-STORE-PROGRESS', callback); }
    onProcessInfo          (callback: (evt : Record<string,ProcessInfo>) => void) : this { return this.onEvent('PROCESS-INFOS', callback); }


    private connectTo(instanceId? : string) {
        getUserInfo().then(userInfo => { // NOSONAR -- complexity is in nested functions
            if(this.isConnected() || this.connecting) return;
            this.connecting = true;
            const host = concatPath(
                location.host,
                localStorage.selectedStarName ? '/@(x-star-target/' + encodeURIComponent(localStorage.selectedStarName) + ')@/' : null,
                userInfo?.token               ? '/@(X-AUTH-TOKEN/' + encodeURIComponent(userInfo.token) + ')@/' : null
            );
            this.instanceId = instanceId;
            const protocol = location.protocol.includes('s') ? 'wss:/' : 'ws:/';
            const webSocket = new WebSocket(concatPath(protocol, host, instanceId, 'event-emitter'));
            webSocket.onopen = () => {
                this.webSocket = webSocket;
                if(this.reconnectingTimerId) { clearInterval(this.reconnectingTimerId); this.reconnectingTimerId = null; }
                if(!this.pingRepeater) this.pingRepeater = setInterval(this.ping, 30_000);
                this.connecting = false;
                console.log('WebSocket connected');
            };
            webSocket.onmessage = msg => {
                this.handleEvent(msg.data ? <EmittedEvent>JSON.parse(msg.data) : {type: '', data: undefined});
            };
            webSocket.onclose = () => {
                console.log('Websocket closed');
                if(this.webSocket && !this.reconnectingTimerId) this.reconnectingTimerId = setInterval(() => this.connectTo(instanceId), 5000);
                if(this.pingRepeater) { clearInterval(this.pingRepeater); this.pingRepeater = null; }
                this.webSocket = null;
                this.connecting = false;
            };
            webSocket.onerror = err => {
                console.warn("WebSocket encountered an error", err);
                this.connecting = false;
                this.pollIfUnconnected();
            };
        });
    }
    private onEvent(type : string, callback: (arg : object) => void) : this {
        let listeners = this.handlers.get(type);
        if(!listeners) { listeners = []; this.handlers.set(type, listeners); }
        listeners.push(callback);
        return this;
    }
    private handleEvent(event : EmittedEvent) : void {
        (this.handlers.get(event.type) || []).forEach(listener => {
            if(/^(UN)?REGISTERED$/.test(event.type)) event.data['id'] = stringToServiceId(event.data['id'] || event.data['serviceId']);
            listener.call(this, event.data);
        });
    }
    private ping() {
        if(EventReceiver.get().isConnected) {
            EventReceiver.get().webSocket?.send('ping');
        }
    }
    private pollIfUnconnected() { // fallback if no websocket
        if(!this.isConnected() && !this.polling) {
            this.polling = true;
            // polling fails after gateway timeout, so expect possible non-json return
            // (like an html 'gateway timeout' error). So use fetchText() instead of fetchData().
            fetchText({ url: '/poll-for-event?since=' + this.lastPollEventTime, showError: false})
                // response.json() throws on empty body, but not text() so use JSON.parse() instead
                .then((text) => {
                    let evt : any = {};
                    try {
                        if(text && !text.trim().startsWith('<')) evt =JSON.parse(text);
                    } catch(textIsNotJson) {
                        evt = {};
                    }
                    this.polling = false;
                    if(typeof evt.timestamp === 'number') this.lastPollEventTime = evt.timestamp + 1;
                    if(!this.isConnected()) { // prevent double events in case the websocket reconnected
                        this.handleEvent(evt);
                        setTimeout(() => this.pollIfUnconnected(), 1500); // delayed in case of loop
                    }
                })
                .finally(() => this.polling = false);
        }
    }
}
