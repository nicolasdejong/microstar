import {get, writable} from 'svelte/store';

export type User = {
  id : string,
  name : string,
  email : string,
  token: string,
  isAdmin: boolean
}

function createUserStore() {
    const { subscribe, set } = writable(null as User);

    const store = {
        subscribe,
        updated: () => set(get(store)),
        reset: () => set(null),
        set: user => set(user),
        setIsAdmin: setValue => set({ ...get(store), isAdmin: setValue }),
        setName: newName => set({ ...get(store), name: newName})
    };
    store.reset();
    return store;
}

export const userStore = createUserStore();
