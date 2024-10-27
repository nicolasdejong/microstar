<script type="ts">
    import ListItem from "../various/SelectionList.svelte";
    import List from "../various/SelectionList.svelte";
    import SelectionList from "../various/SelectionList.svelte";
    import {doFetch, fetchData} from "../../utils/Network";
    import {EventReceiver} from "../../utils/EventReceiver";

    type UserConfiguration = { name: string, target?: string[] }

    let currentDir: string = null;
    let fileLists: List[] = [];
    let selectionLists: SelectionList[] = [];
    let configuredUsers : UserConfiguration[] = [];
    let configuredUsersList: SelectionList = null;
    let selectedUser : UserConfiguration = null;

    EventReceiver.get().onStaticDataChanged(refresh);

    refresh();

    function updateConfiguredUsersList() {
        updateSelectedUser(selectedUser ? { name: selectedUser.name } : null);
        configuredUsersList = {
            items: configuredUsers.map(user => ({
                path: user.target,
                name: user.name,
                canBeDeleted: false,
                canBeStarred: true,
                isStarred: user.target.length,
                isSelected: user.name === selectedUser?.name && selectedUser
            }))
        };
        configuredUsers = configuredUsers; // Svelte update
    }
    function updateSelectedUser(newSelectedUserItem?: ListItem) {
        if(newSelectedUserItem || !selectedUser) selectedUser =
                 configuredUsers?.find(cu => cu.name === newSelectedUserItem?.name && !!newSelectedUserItem)
              || configuredUsers?.find(cu => cu.name === 'default')
              || configuredUsers[0];
        return selectedUser;
    }
    function selectUserTarget(newSelectedUserItem?: ListItem, force?: boolean): Promise<any> {
        if(!newSelectedUserItem && selectedUser && !force) return;
        updateSelectedUser(newSelectedUserItem);
        const userPaths = selectedUser?.target?.map(t => t.replace(/^\//,'').replace(/\/$/,'')+'/'); // let userPaths start with non-slash, end with slash

        if(userPaths?.length) {
            configuredUsersList.items.forEach(item => item.isSelected = item.name === selectedUser?.name);
            configuredUsersList.items = configuredUsersList.items; // Svelte update

            // Only one path at a time can be selected, so even if there are more targets, choose the first
            return updateDir(userPaths[0]).then(() => {
                const selectionPath = userPaths[0].split('/').filter(s => !!s);

                // Set selection
                for(let i=0; i<selectionPath.length; i++) {
                    fileLists[i].items.forEach(item => {
                        item.isSelected = userPaths[0].startsWith(item['path']?.replace(/^\//,''));
                    });
                    fileLists[i].items = fileLists[i].items; // Svelte update
                }

                // Set stars
                fileLists.forEach(fileList => {
                    fileList.items.forEach(item => {
                        item.isStarred = !!userPaths.find(up => isEqualPath(up, item.path));
                    });
                    fileList.items = fileList.items; // Svelte update
                });
            });
        } else {
            return updateDir('').then(() => {
                if (fileLists.length > 0) {
                    fileLists[0].items.forEach(item => {
                        item.isSelected = false;
                        item.isStarred = false;
                    });
                    fileLists[0].items = fileLists[0].items;
                }
            });
        }
    }
    function refresh(setUserConfiguration? : UserConfiguration[]) {
        const cd = currentDir;
        currentDir = null;
        fileLists = [];
        return (setUserConfiguration ? Promise.resolve(setUserConfiguration) : serverGetConfiguredUsers()).then(cu => {
            configuredUsers = cu;
            updateConfiguredUsersList();
            return updateDir(cd).then(() => selectUserTarget(null, true));
        });
    }
    function updateLastDir()  {
        const cd = currentDir;
        currentDir = '';
        fileLists = fileLists.slice(0, Math.max(0, fileLists.length - 1));
        return updateDir(cd);
    }
    function updateDir(newPath: string): Promise<any> {
        const newPathParts    = (newPath    || '').split('/').filter(s => !!s);
        let   currentDirParts = (currentDir || '').split('/').filter(s => !!s);
        newPath = newPathParts.join('/') + ((newPath || '').endsWith('/') ? '/' : '');
        if(currentDir == newPath) return Promise.resolve();
        const newListCount = newPathParts.length + 1;

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
        const userTargets = selectedUser?.target;
        currentDir = newPath;

        for(let i=fileLists.length; i < newListCount; i++) {
            const listIndex = i;
            const dirPath = '/' + newPathParts.filter(s=>!!s).slice(0, listIndex).join('/');
            loading.push(serverGetDir(dirPath)
                .then(dirContents => {
                    fileLists[listIndex] = {
                        path: dirPath,
                        name: newPathParts[listIndex-1] || 'root',
                        singleStarred: false,
                        items: dirContents.map(itemName => {
                            const itemPath = [dirPath.replace(/^\//,''), itemName].join('/');
                            const isStarred = !!userTargets.find(ut => isEqualPath(ut, itemPath));
                            return {
                                path: itemPath,
                                name: itemName,
                                canBeStarred: itemName.endsWith('/'),
                                canBeDeleted: true,
                                canBeRenamed: true,
                                isStarred
                            };
                        })
                    };
                    fileLists = fileLists; // Svelte update
                })
            );
        }
        return Promise.all(loading);
    }
    function handleSelection(item: ListItem) {
        if(!item.path.endsWith('/')) {
            return updateDir(item.path.replace(/[^\/]+$/,''));
        }
        return currentDir == item.path
            ? updateLastDir()
            : updateDir(item.path).then(() => selectUserTarget());
    }

    function addDir(fileList: List): Promise<boolean> {
        const newName = new Date()
                .toISOString()           // 2011-10-05T14:48:00.000Z
                .replace(/\.\d{3}.$/,'') // 2011-10-05T14:48:00
                .replace(/T/,'.')        // 2011-10-05.14:48:00
                .replace(/[^\d.]/g,'')   // 20111005.144800
            + '/';
        const newItem : ListItem = <ListItem>{
            canBeDeleted: true,
            canBeRenamed: true,
            isSelected: true,
            name: newName,
            path: [fileList.path, newName].join('/')
        };
        fileList.items.forEach(item => item.isSelected = false);
        fileList.items.unshift(<any>newItem);
        fileList.items.sort((a,b) => a.name.localeCompare(b.name));
        fileList.items = fileList.items; // Svelte update

        const flIndex = fileLists.indexOf(fileList);
        fileLists.length = flIndex + 1;
        return serverCreateDirectory(newItem.path).then(isOk => {
            if(isOk) {
                // noinspection TypeScriptValidateTypes
                selectionLists[flIndex].rename();
            }
            return isOk;
        });
    }
    function handleRename(list: List, item: ListItem, newName: string, oldName: string): Promise<boolean> {
        const isDir = oldName.endsWith('/');
        newName = newName.replace(/\/+$/,'').replace(/[\/\\]/g, '_');

        const oldPath = item.path;
        const newPath = item.path = concatPath(item.path.replace(/[^\/]+\/?$/, ''), newName) + (isDir ? '/' : '');
        const userForPath = configuredUsers.find(cu => cu.target.find(e => e === oldPath));
        if(userForPath) {
            userForPath.target = userForPath.target.filter(p => p !== oldPath); userForPath.target.push(newPath);
            configuredUsers = configuredUsers; // Svelte update
            serverSetUserTarget(selectedUser.name, newPath);
        }
        item.name = item.text = newName + (isDir ? '/' : '');
        item.path = newPath;
        if(isDir) currentDir = newPath; // dir-rename
        list.items = list.items; // Svelte update
        return serverRename(concatPath(list.path, oldName), concatPath(list.path, newName))
            .then(_ => updateLastDir());
    }
    function handleDelete(list: List, item: ListItem): Promise<boolean> {
        const isDir = item.name.endsWith('/');
        if(isDir) currentDir = list.path.replace(/\/$/,'') + '/';
        list.items = list.items.filter(listItem => listItem !== item);
        return serverDelete(item.path).then(_ => updateLastDir());
    }
    function handleStarred(item: ListItem): Promise<any> {
        if(selectedUser) {
            if(item.isStarred) { if(selectedUser.target.indexOf(item.path)<0) selectedUser.target.push(item.path); }
            else               { selectedUser.target = selectedUser.target.filter(t => !isEqualPath(t, item.path)); }

            return (item.isStarred
                ? serverSetUserTarget(selectedUser.name, item.path).then(refresh)
                : serverSetUserTargets().then(() => refresh(configuredUsers))
            );
        }
        return Promise.resolve(true);
    }
    function handleUnstarredUser(): Promise<boolean> {
        selectedUser.target = [];
        updateConfiguredUsersList();
        fileLists.forEach(fileList => fileList.items.forEach(item => item.isStarred = false));
        fileLists = fileLists; // Svelte update
        return serverSetUserTargets().then(() => refresh(configuredUsers));
    }


    function serverSetUserTarget(user: string, target: string): Promise<any> {
        return fetchData({ url: concatPath('/microstar-statics/user-target/' + user, target), method: 'post'})
            .then(data => Object.entries(data)
                .map(entry => (<UserConfiguration>{ name: entry[0], target: entry[1] }))
            ).then(refresh);
    }
    function serverSetUserTargets(): Promise<boolean> {
        const usersToTargets = new Map();
        configuredUsers.forEach(cu => usersToTargets[cu.name] = cu.target);
        return doFetch({
            url: '/microstar-statics/user-targets',
            method: 'post',
            jsonBody: usersToTargets
        }).then(resp => resp.status === 200);
    }
    function serverRename(from: string, to: string): Promise<boolean> {
        return doFetch(concatPath('/microstar-statics/dir/rename/', from) + '?to=' + to, 'post').then(response => response.status === 200);
    }
    function serverDelete(path: string): Promise<boolean> {
        return doFetch(concatPath('/microstar-statics/dir/', path), 'delete').then(response => response.status === 200);
    }
    function serverCreateDirectory(name: string): Promise<boolean> {
        return doFetch(concatPath('/microstar-statics/dir/', name), 'post').then(response => response.status === 200);
    }
    function serverGetConfiguredUsers(url?: string, method?: string): Promise<UserConfiguration[]> {
        return fetchData('/microstar-statics/user-targets', method || 'get')
            .then(data => Object.entries(data)
                .map(entry => (<UserConfiguration>{ name: entry[0], target: entry[1] }))
            );
    }
    function serverGetDir(name: string): Promise<any> {
        return fetchData('/microstar-statics/dir' + name);
    }

    function onUploadSelected(evt, index) {
        const fileList = fileLists[index];
        const files = evt.target.files;
        const formData = new FormData();
        if(!files || !files[0]) return;

        Array.from(files).forEach((file : File) => formData.append(file.name, file));
        return doFetch(concatPath('/upload/', fileList.path), 'POST', formData)
          .finally(() => { updateLastDir(); });
    }

    function concatPath(...parts: string[]): string {
        return (parts[0]?.startsWith('/') ? '/' : '') + parts.map(part => part.replace(/^\/+|\/+$/g,'')).join('/');
    }
    function isEqualPath(a: string, b: string) {
        return a.replace(/^\/|\/$/g, '') === b.replace(/^\/|\/$/g, '');
    }
</script>

<div class="StaticDataPanel">
    <div class="FileLists">
        <!-- users -->
        <div class="FileList">
            <div class="Header">
                <span style="visibility:hidden;">W</span>
                <span class="name">Users</span>
                <span class="separator"></span>
                <span class="buttons">
                    <!--span title="add user" on:click={_ => addDir(fileList)}>&#10009;</span-->
                    <span title="refresh" on:click={_ => { refresh(); }}>&#9851;</span>
                </span>
            </div>
            <div class="Content">
                <SelectionList list="{configuredUsersList}"
                               on:lastSelection={evt => selectUserTarget(evt.detail.item)}
                               on:starred={() => handleUnstarredUser()}
                ></SelectionList>
            </div>
        </div>

        <div class="Arrow">&#8680;</div>

        <!-- folders -->
        {#each fileLists as fileList, index}
            {#if fileList}
            <div class="FileList">
                <div class="Header">
                    <span style="visibility:hidden;">W</span>
                    <span class="name">{fileList?.name}</span>
                    <span class="separator"></span>
                    <span class="buttons">
                        <span                title="add dir" on:click={_ => addDir(fileList)}>&#10009;</span>
                        <span class="upload" title="upload"  on:click={evt => { evt.target.nextElementSibling.click(); }} style="user-select: none; display: inline-block; transform:rotate(270deg);">&#10144;</span>
                        <input style="display:none" type="file" multiple on:change={(e)=>onUploadSelected(e,index)}>
                    </span>
                </div>
                <div class="Content">
                    <SelectionList list={fileList}
                                   bind:this={selectionLists[index]}
                                   on:lastSelection={evt => handleSelection(evt.detail.item)}
                                   on:starred={evt => handleStarred(evt.detail.item)}
                                   on:rename={evt => handleRename(evt.detail.list, evt.detail.item, evt.detail.newName, evt.detail.oldName)}
                                   on:delete={evt => handleDelete(fileList, evt.detail.item)}
                    ></SelectionList>
                </div>
            </div>
            {/if}
        {/each}
    </div>

<!-- configured targets -->
    <table class="targets">
        <tr>
            <th>User</th>
            <th>Target</th>
        </tr>
        {#each configuredUsers as configuredUser}
            <tr>
                <td>{configuredUser.name}</td>
                <td>{configuredUser.target || '<unset>'}</td>
            </tr>
        {/each}
    </table>

</div>

<style lang="scss">
  .StaticDataPanel {
    display: flex;
    flex-direction: column;
    height: 100%;
  }

  .FileLists {
    display: flex;
    flex-direction: row;
    margin: 0.25em 0.25em 0 0.25em;
    overflow: auto;
    flex: 1;
    min-height: 20em;

    div.Arrow {
      display: grid;
      align-items: center;
      justify-content: center;
      font-size: 500%;
      margin-bottom: 0.5em;
    }

    .FileList {
      display: flex;
      flex-direction: column;
      min-width: fit-content;
      .Header {
        padding: 0 0.25em;
        background-color: var(--background-color-label);
        border: 1px solid var(--border-color);
        border-width: 0 1px 0 1px;
        white-space: nowrap;
        position: relative;
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
        min-width: 100%;
        overflow: auto;
        border: 1px solid var(--border-color);
      }
    }
  }

  table.targets {
    display: block;
    margin: 1em;
    border-collapse: collapse;
    $border: 1px solid var(--border-color);
    th { border-bottom: $border; }
    th:first-child, td:first-child { border-right: $border; }
    td { padding: 0 1em; }
  }
</style>