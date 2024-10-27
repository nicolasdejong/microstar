import {derived, get, writable} from 'svelte/store';
import type {Service, Services} from "../types/Services";
import {stringToServiceId} from "../types/Services";
import {fetchData} from "../utils/Network";
import {debounceAsPromise} from "../utils/Utils";

function createServicesStore() {
    const { subscribe, set, update } = writable([] as Service[]);

    const store = {
        subscribe,
        refresh: () => {
            return debounceAsPromise(() => getServices().then((services) => update(_ => services.services)));
        },
        updated: () => set(get(store)),
        reset: () => set([]),
        get: () => get(store)
    };
    store.reset();
    store.refresh();
    return store;

    async function getServices() : Promise<Services> {
        const services: Services = await fetchData('/services')
            .catch(error => {
                console.log('Error getting services:', error?.message || error);
                return { services: [] };
            });
        services.services = services.services
            .map(service => { service.id = stringToServiceId(service.id.combined || String(service.id)); return service; })
            .sort(sortService);
        setNewestFlags(services.services);
        return services;
    }

    function sortService(a: Service, b: Service) {
        const aIsDispatcher = a.id.name === 'microstar-dispatcher';
        const bIsDispatcher = b.id.name === 'microstar-dispatcher';
        if( aIsDispatcher && !bIsDispatcher) return -1;
        if(!aIsDispatcher &&  bIsDispatcher) return  1;

        const aIsMicrostar = a.id.name.startsWith('microstar');
        const bIsMicrostar = b.id.name.startsWith('microstar');
        if( aIsMicrostar && !bIsMicrostar) return -1;
        if(!aIsMicrostar &&  bIsMicrostar) return  1;

        const nameDiff = a.id.name.localeCompare(b.id.name);
        if(nameDiff != 0) return nameDiff;

        // SNAPSHOTS are older than non-snapshots of the same version
        const aVersion = a.id.version + a.id.version.includes('-SNAPSHOT') ? '' : '-ZZZ';
        const bVersion = b.id.version + b.id.version.includes('-SNAPSHOT') ? '' : '-ZZZ';

        const verDiff = aVersion.localeCompare(bVersion);
        return -verDiff;
    }
    function setNewestFlags(services: Service[]) : void {
        let previousServiceName = '';
        let versionCount = 1;
        let maxVersionCount = 1;
        services.forEach(service => {
            if(service.state === 'DORMANT') {
                service.isNewest = service.id.name != previousServiceName;
                if(previousServiceName === service.id.name) maxVersionCount = Math.max(maxVersionCount, ++versionCount);
                else { previousServiceName = service.id.name; versionCount = 1; }
            }
        });
        if(maxVersionCount == 1) services.forEach(service => service.isNewest = false);
    }
}


export const servicesStore = createServicesStore();
export const serviceNamesStore = derived(servicesStore ,(services) => {
    return services
        .map(service => service.id.name)
        .unique()
        .sort();
});
export const nameToServicesStore = derived(servicesStore, (services) => {
    return services
        .reduce((result, service) => {
            if(!result[service.id.name]) result[service.id.name] = [];
            result[service.id.name].push(service);
            return result;
        }, {})
});