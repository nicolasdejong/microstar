
export type ServiceId = {
    group: string;
    name: string;
    version: string;
    combined: string; // group-name-version
}
export function stringToServiceId(idTextOrType : string | ServiceId) : ServiceId {
    if(idTextOrType['name']) return <ServiceId>idTextOrType;
    if(typeof idTextOrType !== 'string') { console.log('idText is not a string!:', idTextOrType); idTextOrType = ''; }
    const parts = (idTextOrType || '?/?/?').split(/\//);
    return {group: parts[0], name: parts[1], version: parts[2], combined: idTextOrType};
}

export type Service = {
    id: ServiceId;
    state: string;
    instanceId: string;
    requestCount1m: number;
    requestCount10m: number;
    requestCount8h: number;
    requestCount24h: number;
    runningSince: number;
    trafficPercent: number;
    isNewest?: boolean;
    processInfo?: ProcessInfo;
}

export type ProcessInfo = {
    pid: number;
    virtualMemorySize: string;
    residentMemorySize: string;
    metaSpace: string;
    heapSize: string;
    heapUsed: string;
    heapUsedPercent: number;
    minHeapUsed: string;
    uptime: string;
    sysMem: string;
    sysMemAvailable: string;
    sysMemAvailablePercent: string;
}

export type Services = {
    services: Service[];
}
