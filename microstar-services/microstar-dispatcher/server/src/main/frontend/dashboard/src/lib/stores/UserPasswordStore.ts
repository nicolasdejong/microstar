import {get, writable} from 'svelte/store';
import {fromBase64, toBase64} from "../utils/Utils";

const LS_KEY = 'microstar.up';

export type UserPassword = {
  username : string,
  password : string
}

function createUserPasswordStore() {
    const { subscribe, set } = writable(null as UserPassword);

    const store = {
        subscribe,
        resetPassword: () => store.set(get(store)?.username, null),
        set: (username: string, password: string) => {
            const up: UserPassword = {username, password};
            localStorage[LS_KEY] = toBase64(JSON.stringify(up));
            set(up);
        },
        get: () => get(store),
        reset: () => store.set(null, null),
        trigger: () => set(store.get())
    };
    const stored = JSON.parse(fromBase64(localStorage[LS_KEY]) || '{}');
    if(stored) store.set(stored.username, stored.password);
    return store;
}

export const userPasswordStore = createUserPasswordStore();
