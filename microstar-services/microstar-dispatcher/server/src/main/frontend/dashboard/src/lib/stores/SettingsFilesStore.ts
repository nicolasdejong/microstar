import {get, writable} from 'svelte/store';
import {fetchData} from "../utils/Network";

export type SettingsFile = {
    name: string,
    isDeleted: boolean
}

function createSettingsFilesStore() {
    const { subscribe, set, update } = writable([] as SettingsFile[]);

    const store = {
        subscribe,
        refresh: () => {
            return getFiles().then(filenames => update(_ => filenames || []));
        },
        updated: () => set(get(store)),
        reset: () => set([]),
        rename: (oldName, newName) => {
            const items = get(store);
            const target = items.find(item => item.name === oldName);
            if(target) target.name = newName;
            set(items);
        },
        add: name => {
            const items = get(store);
            items.unshift({name,isDeleted:false});
            set(items);
        }
    };
    store.reset(); // in case refresh fails
    store.refresh();
    return store;

    async function getFiles() : Promise<SettingsFile[]> {
        return fetchData({ url: '/microstar-settings/files', showError: false }).catch(_error => []);
    }
}

export const settingsFilesStore = createSettingsFilesStore();
