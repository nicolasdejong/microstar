import {get, writable} from 'svelte/store';
import {fetchData} from "../utils/Network";

export type DashboardSettings = {
  banner? : {
      title: string,
      color?: string,
      bgcolor?: string
  }
  foo? : string,
  n? : number;
}

function createDashboardSettingsStore() {
    const { subscribe, set, update } = writable(null as DashboardSettings);

    const store = {
        subscribe,
        refresh: () => {
            return getSettings().then(settings => update(_ => settings));
        },
        updated: () => set(get(store)),
        reset: () => set({}),
    };

    store.reset();
    store.refresh();
    return store;

    async function getSettings() : Promise<DashboardSettings> {
        return <DashboardSettings>await fetchData('/combined-settings/frontend.dashboard')
            .catch((error) => {
                console.log('Error getting dashboard settings:', error?.message || error);
                return {};
            });
    }
}

export const dashboardSettingsStore = createDashboardSettingsStore();
