import {get, writable} from 'svelte/store';
import {fetchData} from "../utils/Network";

export type Version = {
    version: number,
    modifiedTime: number,
    user: string,
    actionType: string,
    userMessage?: string
}

function createSettingsUsersStore() {
    const { subscribe, set, update } = writable([] as Version[]);
    let targetName = '';

    const store = {
        subscribe,
        refresh: () => {
            return getSettingsUsersFromServer().then(filenames => update(_ => filenames));
        },
        setFile: name => { targetName = name; return store.refresh(); },
        updated: () => set(get(store)),
        reset: () => set([])
    };
    store.reset();
    store.refresh();
    return store;

    async function getSettingsUsersFromServer() : Promise<Version[]> {
        return targetName ? fetchData('/microstar-settings/services-using/' + targetName).catch(_error => []) : Promise.resolve([]);
    }
}

export const settingsUsersStore = createSettingsUsersStore();
