<script lang="ts">
    import type List from "../various/SelectionList.svelte";
    import type ListItem from "../various/SelectionList.svelte";
    import SelectionList from "../various/SelectionList.svelte";
    import {doFetch, fetchData} from "../../utils/Network";
    import {onMount} from "svelte";
    import {EventReceiver} from "../../utils/EventReceiver";
    import {concatPath, debounce} from "../../utils/Utils";

    export type SelectedItem = {
    storeName: string;
    path: string;
    count: number;
    size: number;
}
export let selectedItem: SelectedItem = null;

let dataSources : string[] = []; // updated by refresh()
let dataSourcesList: List;
let selectedStore: string;
let fileLists : List[] = [];
let selectionLists: SelectionList[] = [];
let currentDir: string = null;
let topPanel: HTMLDivElement;

EventReceiver.get()
    .onDataStoreChanged(evt => {
        if(selectedStore === evt.name) debounce(refresh, 500);
    })
    .onDataStoresChanged(() => refresh());

onMount(() => { refresh(); });

function updateStoresList() {
    updateSelectedStore(selectedStore ? <ListItem>(<unknown>{ name: selectedStore }) : null);
    dataSourcesList = <List>(<unknown>{
        items: dataSources.map(dsName => <ListItem>(<unknown>{
            name: dsName,
            canBeDeleted: false,
            canBeStarred: false,
            isSelected: dsName === selectedStore
        }))
    });
}
function updateSelectedStore(newSelectedStoreItem?: ListItem): string {
    if(newSelectedStoreItem || !selectedStore) selectedStore =
        dataSourcesList?.items?.find(ds => ds.name === newSelectedStoreItem?.name && !!newSelectedStoreItem)?.name;
    if(selectedStore) {
        selectedItem = {
            storeName: selectedStore,
            path: '',
            count: -1,
            size: -1
        };
    }
    return selectedStore;
}
async function selectStore(newStoreItem?: ListItem, force?: boolean): Promise<any> {
    if(!newStoreItem && selectedStore && !force) return;
    updateSelectedStore(newStoreItem);
    currentDir = null;
    fileLists = [];

    return updateDir('').then(() => {
            if (fileLists.length > 0) {
                fileLists[0].items.forEach(item => {
                    item.isSelected = false;
                    item.canBeStarred = false;
                });
                fileLists[0].items = fileLists[0].items; // Svelte update
            }
        });
}

async function refresh() {
    const cd = currentDir;
    currentDir = null;
    fileLists = [];
    return serverGetStores().then(dsList => {
        dataSources = dsList;
        updateStoresList();
        return updateDir(cd).then(() => selectStore(null, true));
    });
}
async function updateLastDir()  {
    const cd = currentDir;
    currentDir = '';
    fileLists = fileLists.slice(0, Math.max(0, fileLists.length - 1));
    return updateDir(cd);
}
async function updateDir(newPath: string): Promise<any> {
    const newPathParts    = (newPath    || '').split('/').filter(s => !!s);
    let   currentDirParts = (currentDir || '').split('/').filter(s => !!s);
    newPath = newPathParts.join('/') + ((newPath || '').endsWith('/') ? '/' : '');
    if(currentDir === newPath) return Promise.resolve();
    const newListCount = selectedStore ? (newPathParts.length + 1) : 0;

    function trimListsTo(size: number) {
        currentDirParts = currentDirParts.slice(0, size);
        currentDir = currentDirParts.join('/');
        fileLists  = fileLists.slice(0, size);
    }

    for(let i=0; i<currentDirParts.length && i < newPathParts.length; i++) {
        if(currentDirParts[i] != newPathParts[i]) { trimListsTo(i + 1); break; }
    }
    if(newListCount < currentDirParts.length + 1) trimListsTo(newListCount);
    if(newListCount === fileLists.length) return Promise.resolve(); // nothing to add

    const loading = [];
    currentDir = newPath;

    for(let i=fileLists.length; i < newListCount; i++) {
        const listIndex = i;
        const dirPath = '/' + newPathParts.filter(s=>!!s).slice(0, listIndex).join('/');
        loading.push(serverGetDir(dirPath)
            .then(dirContents => {
                fileLists[listIndex] = <List>(<unknown>{
                    path: dirPath,
                    name: newPathParts[listIndex-1] || 'root',
                    singleStarred: false,
                    items: dirContents.map(item => {
                        return {
                            path: [dirPath.replace(/^\//,''), item.path].join('/'),
                            name: item.path,
                            item,
                            canBeDeleted: true,
                            canBeRenamed: true
                        };
                    })
                });
                fileLists = fileLists; // Svelte update
            })
        );
    }
    return Promise.all(loading)
        .then(() => setTimeout(() => { if(topPanel) topPanel.scrollLeft = topPanel.scrollWidth; }, 1));
}
async function handleSelection(item: ListItem) {
    selectedItem = {
        storeName: selectedStore,
        path: item.path,
        count: item.item.count,
        size: item.item.size
    };
    if(!item.path.endsWith('/')) {
        return updateDir(item.path.replace(/[^\/]+$/,''));
    }
    return currentDir == item.path
        ? updateLastDir()
        : updateDir(item.path).then(() => selectStore());
}

async function addDir(fileList: List): Promise<boolean> {
    const newName = new Date()
            .toISOString()           // 2011-10-05T14:48:00.000Z
            .replace(/\.\d{3}.$/,'') // 2011-10-05T14:48:00
            .replace(/T/,'.')        // 2011-10-05.14:48:00
            .replace(/[^\d.]/g,'')   // 20111005.144800
        + '/';
    const newItem : ListItem = <ListItem>(<unknown>{
        canBeDeleted: true,
        canBeRenamed: true,
        isSelected: true,
        name: newName,
        path: [fileList.path, newName].join('/')
    });
    fileList.items.forEach(item => item.isSelected = false);
    fileList.items.unshift(<any>newItem);
    fileList.items.sort((a,b) => a.name.localeCompare(b.name));
    fileList.items = fileList.items; // Svelte update

    const flIndex = fileLists.indexOf(fileList);
    fileLists.length = flIndex + 1;

    return serverCreateDirectory(newItem.path).then(isOk => {
        if(isOk) {
            // noinspection TypeScriptValidateTypes
            updateLastDir().then(() => selectionLists[flIndex]?.rename());
        }
        return isOk;
    });
}
async function handleRename(list: List, item: ListItem, newName: string, oldName: string): Promise<boolean> {
    const isDir = oldName.endsWith('/');
    newName = newName.replace(/\/+$/,'').replace(/[\/\\]/g, '_');

    const newPath = item.path = concatPath(item.path.replace(/[^\/]+\/?$/, ''), newName) + (isDir ? '/' : '');
    item.name = item.text = newName + (isDir ? '/' : '');
    item.path = newPath;
    if(isDir) currentDir = newPath; // dir-rename
    list.items = list.items; // Svelte update
    return serverRename(concatPath(list.path, oldName), concatPath(list.path, newName))
        .then(_ => updateLastDir());
}
async function handleDelete(list: List, item: ListItem): Promise<boolean> {
    const isDir = item.name.endsWith('/');
    if(isDir) currentDir = list.path.replace(/\/$/,'') + '/';
    list.items = list.items.filter(listItem => listItem !== item);

    const flIndex = fileLists.indexOf(list);
    if(flIndex >= 0) fileLists.length = flIndex + 1;

    return serverDelete(item.path + (isDir ? '/' : ''));
}

async function serverGetStores() : Promise<string[]> {
    return fetchData('/datastore');
}
async function serverRename(from: string, to: string): Promise<boolean> {
    return doFetch(concatPath('/datastore/', selectedStore, 'rename', from) + '?to=' + to, 'post').then(response => response.status === 200);
}
async function serverDelete(path: string): Promise<boolean> {
    return doFetch(concatPath('/datastore/', selectedStore, 'delete', path), 'post').then(response => response.status === 200);
}
async function serverCreateDirectory(newPath: string): Promise<boolean> {
    return doFetch(concatPath('/datastore/', selectedStore, 'createDir', newPath), 'post')
        .then(response => response.status === 200);
}
async function serverGetDir(path: string): Promise<any> {
    if(!selectedStore) return Promise.resolve([]);
    return fetchData(concatPath('/datastore/', selectedStore, 'list', path));
}

async function onUploadSelected(evt: any, index: number): Promise<any> {
    const fileList = fileLists[index];
    const files = evt.target.files;
    const formData = new FormData();
    if(!files || !files[0]) return;

    Array.from(files).forEach((file : File) => formData.append(file.name, file));
    return doFetch(concatPath('/datastore/', selectedStore, 'upload', fileList.path), 'POST', formData)
        .finally(() => { updateLastDir(); });
}

</script>

<div class="DataStores" bind:this={topPanel}>
    <div class="FileList">
        <div class="Header">
            <span style="visibility:hidden;">W</span>
            <span class="name">Data Stores</span>
            <span class="separator"></span>
            <span class="buttons">
                <span title="refresh" on:click={_ => { refresh(); }} on:keydown={()=>{}}>&#9851;</span>
            </span>
        </div>
        <div class="Content">
            <SelectionList list={dataSourcesList}
                           on:lastSelection={evt => selectStore(evt.detail.item)}
            ></SelectionList>
        </div>
    </div>

    {#each fileLists as fileList, index}
        {#if fileList}
            <div class="FileList">
                <div class="Header">
                    <span style="visibility:hidden;">W</span>
                    <span class="name">{fileList?.name}</span>
                    <span class="separator"></span>
                    <span class="buttons">
                    <span                title="add dir" on:click={_ => addDir(fileList)} on:keydown={()=>{}}>&#10009;</span>
                    <span class="upload" title="upload"  on:click={evt => { evt.target.nextElementSibling.click(); }} on:keydown={()=>{}} style="user-select: none; display: inline-block; transform:rotate(270deg);">&#10144;</span>
                    <input style="display:none" type="file" multiple on:change={(e)=>onUploadSelected(e,index)}>
                </span>
                </div>
                <div class="Content">
                    <SelectionList list={fileList}
                                   bind:this={selectionLists[index]}
                                   on:lastSelection={evt => handleSelection(evt.detail.item)}
                                   on:rename={evt => handleRename(evt.detail.list, evt.detail.item, evt.detail.newName, evt.detail.oldName)}
                                   on:delete={evt => handleDelete(fileList, evt.detail.item)}
                    ></SelectionList>
                </div>
            </div>
        {/if}
    {/each}
</div>



<style lang="scss">

  .DataStores {
    display: flex;
    flex-direction: row;
    margin: 0.25em 0.25em 0 0.25em;
    overflow: auto;
    flex: 1;
    min-height: 5em;
    height: 100%;
    padding-bottom: 0.5em;

    .FileList {
      display: flex;
      flex-direction: column;
      min-width: max(7em, fit-content);
      .Header {
        padding: 0 0.25em;
        color: white;
        background-color: var(--background-color-label);
        border: 1px solid var(--border-color);
        border-width: 0 1px 0 1px;
        white-space: nowrap;
        position: relative;
        overflow: hidden;
        > * { background-color: var(--background-color-label); }
        .name { position: absolute; left: 0.25em;  }
        .buttons {
          position: absolute; right: 0.1em; display:inline-block; padding-left: 0.5em; z-index: 1; cursor: pointer;
          color: white;
          > span:hover { display:inline-block; scale: 1.4; }
        }
      }

      .Content {
        height: 100%;
        width: fit-content;
        min-width: 7em;
        overflow: auto;
        border: 1px solid var(--border-color);
      }
    }
  }
</style>
