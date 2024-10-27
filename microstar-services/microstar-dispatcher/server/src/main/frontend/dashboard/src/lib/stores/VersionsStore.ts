import {get, writable} from 'svelte/store';
import {fetchData} from "../utils/Network";

export type Version = {
    version: number,
    modifiedTime: number,
    user: string,
    actionType: string,
    userMessage?: string
}

function createVersionsStore() {
    const { subscribe, set, update } = writable([] as Version[]);
    let targetName = '';

    const store = {
        subscribe,
        refresh: () => {
            return getVersionsFromServer().then(filenames => update(_ => filenames));
        },
        setFile: name => { targetName = name; return store.refresh(); },
        updated: () => set(get(store)),
        reset: () => set([])
    };
    store.reset();
    store.refresh();
    return store;

    async function getVersionsFromServer() : Promise<Version[]> {
        return targetName ? fetchData('/microstar-settings/versions/' + targetName).catch(_error => []) : Promise.resolve([]);
    }
}

export const versionsStore = createVersionsStore();
