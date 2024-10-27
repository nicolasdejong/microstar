import {get, writable} from 'svelte/store';
import {doFetch} from "../utils/Network";

export type StarProperties = {
  url: string,
  name: string,
  isActive: boolean
}
export type StarsProperties = {
  currentName: string,
  stars: StarProperties[];
}

function createStarPropertiesStore() {
    const { subscribe, set, update } = writable(null as StarsProperties);

    const store = {
        subscribe,
        refresh: () => {
            return getProperties().then(stars => {
                update(_ => stars);
                if(!store.getCurrentStar()?.isActive && stars.stars && stars.stars[0]?.isActive) {
                    store.setCurrentName(stars.stars[0]?.name || null);
                    location.reload();
                }
            })
        },
        updated: () => set(get(store)),
        reset: () => set(<StarsProperties>{stars:[], currentName: localStorage.selectedStarName}),
        setCurrentName: name => {
            get(store).currentName = name;
            if(name) localStorage.selectedStarName = name; else delete localStorage.selectedStarName;
            store.updated();
        },
        getStarForName: name => (get(store).stars || []).filter(star => star.name === name)[0],
        getCurrentStar: () => store.getStarForName(get(store).currentName),
        getLocalStar: () => get(store).stars.filter(star => location.href.startsWith(star.url))[0],
        isOnLocalStar: () => store.getCurrentStar().url === store.getLocalStar().url
    };

    store.reset();
    setTimeout(() => store.refresh(), 100); // doFetch uses this store so prevent loops by delaying the init
    return store;

    async function getProperties() : Promise<StarsProperties> {
        return <StarsProperties>await doFetch('/stars')
            .then(response => response.text()
                .then(text => <StarsProperties>{
                    currentName: localStorage.selectedStarName || response.headers.get("X-STAR-NAME") || 'main',
                    stars : JSON.parse(text)
                })
            )
            .catch((error) => {
                console.log('Error getting dispatcher settings:', error?.message || error);
                return {};
            });
    }
}

export const starPropertiesStore = createStarPropertiesStore();
