import {get, writable} from 'svelte/store';

export type ServerError = {
  name : string,
  serviceGroup : string,
  version : string,
  type : string,
  error : string,
  stacktrace : string
}

function createErrorStore() {
    const { subscribe, set } = writable(null as ServerError);

    const store = {
        subscribe,
        updated: () => set(get(store)),
        reset: () => { set(null); if(store.reloadOnReset) setTimeout(() => location.reload(), 500); },
        setError: (error/*may be ServerError*/) => set(error),
        hasError: () => !!get(store),
        reloadOnReset: false
    };
    store.reset();
    return store;
}

export const errorStore = createErrorStore();
