import {get, writable} from 'svelte/store';
import type {Service} from "../types/Services";

function createStore() {
    const { subscribe, set } = writable(null as Service);

    return {
        subscribe,
        set,
        get
    };
}

export const selectedServiceStore = createStore();
