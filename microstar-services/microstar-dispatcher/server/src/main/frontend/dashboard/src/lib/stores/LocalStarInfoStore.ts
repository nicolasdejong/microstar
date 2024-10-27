import {writable} from 'svelte/store';
import {doFetch} from "../utils/Network";

export type LocalStarInfo = {
  starName: string,
  starUrl: string,
  dispatcherUrl: string,
  ipAddresses: []
}

function createStore() {
    const { subscribe, set } = writable(null as LocalStarInfo);

    const store = {
        subscribe,
        refresh: () => getFromServer().then(set),
    };

    store.refresh();
    return store;

    async function getFromServer() : Promise<LocalStarInfo> {
        return <LocalStarInfo>await doFetch('/star')
            .then(response => response.text()
                .then(text => <LocalStarInfo>JSON.parse(text))
            )
            .catch((error) => {
                console.log('Error getting local star properties:', error?.message || error);
                return {};
            });
    }
}

export const localStarInfoStore = createStore();
