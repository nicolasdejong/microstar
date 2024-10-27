<script lang="ts">
    import SplitPanel from "../various/SplitPanel.svelte";
    import DataStoresPanel from "./DataStoresPanel.svelte";
    import SelectedItem from "./DataStoresPanel.svelte";
    import type {ProgressEvent} from "../../utils/EventReceiver";
    import {EventReceiver} from "../../utils/EventReceiver";
    import {concatPath, sizeToString} from "../../utils/Utils.js";
    import {doFetch, fetchData, fetchText} from "../../utils/Network";
    import {decorateLog, escapeRawLog} from "../../utils/Logging.js";

    let selectedFromItem : SelectedItem;
let selectedToItem : SelectedItem;

let copyId: string = null;
let copyProgress : ProgressEvent = { sizeDone: 0, size: 0, countDone: 0, count: 0, id:'', message:'', error:'' };

EventReceiver.get()
    .onDataStoreProgress(evt => copyProgress = evt);

function noNeg(num : number, textIfNegative: string) {
    return num < 0 || isNaN(num) ? textIfNegative : num;
}
function iframeLoaded(iframe : EventTarget) {
    const styles = document.createElement("style");
    styles.textContent = "pre { white-space: pre !important; }";
    (<HTMLIFrameElement>iframe).contentDocument.head.appendChild(styles);
}
function progressPercent(progress: ProgressEvent): number {
    return progress?.size ? Math.floor(100 * (progress.sizeDone / progress.size)) : 0;
}
async function getLogHtml(url: string): Promise<string> {
    return fetchText(url).then(logText => decorateLog(escapeRawLog(logText)));
}
async function copy(): Promise<number> {
    const sourceStore = selectedFromItem.storeName;
    const targetStore = selectedToItem.storeName;
    const sourcePath  = selectedFromItem.path;
    const targetPath  = concatPath(selectedToItem.path, (selectedFromItem.path.match(new RegExp('[^/]+/?$'))||[])[0] || '');
    const url = concatPath('/datastore/', sourceStore, 'copy', sourcePath) + "?targetStore=" + targetStore + '&targetPath=' + targetPath;
    return fetchData({url,method:'POST'}).then(response => copyId = response);
}
function isCopying(id: string, progress: ProgressEvent): boolean { // id and progress are given so Svelte knows when to call this function
    return id && progress && progress.sizeDone && progress.sizeDone < progress.size;
}
function stopCopy(): void {
    if(copyId) {
        doFetch({url:'/datastore/stop/' + copyId, method:'POST'});
        copyId = null;
    }
}
</script>

<SplitPanel leftRight storeName="ds1" defaultPos="60">
    <div slot="left">
        <SplitPanel topBottom storeName="ds2" defaultPos="60" floatingTitle>
            <div slot="top" style="height:100%;padding-bottom:1em;">
                <DataStoresPanel bind:selectedItem={selectedFromItem}></DataStoresPanel>
            </div>
            <div slot="line">{#if isCopying(copyId, copyProgress) }
                Copy progress: <div class="progress-bar" style:background={'linear-gradient(to right, blue '+progressPercent(copyProgress)+'%,transparent '+progressPercent(copyProgress)+'%,transparent 100%)'}></div>
                {copyProgress.countDone}/{copyProgress.count} ({sizeToString(copyProgress.sizeDone)} of {sizeToString(copyProgress.size)})
                <button on:click={() => stopCopy()}>Stop</button>
            {:else}
                {#if selectedFromItem && selectedToItem}
                    Copy from store "{selectedFromItem.storeName}" {noNeg(selectedFromItem.count, 'all')} files ({sizeToString(selectedFromItem.size)}) to store "{selectedToItem.storeName}"
                    <button on:click={() => copy()}>Copy</button>
                {:else}
                    No copy {selectedFromItem?'':'source'} {selectedToItem?'':(selectedFromItem ? '':' and ') + (selectedToItem?'':'target')} selected
                {/if}
            {/if}</div> <!-- slot=line has css :empty which does not support whitespaces -->
            <div slot="bottom" style="height:100%;padding-top:1.2em;">
                <DataStoresPanel bind:selectedItem={selectedToItem}></DataStoresPanel>
            </div>
        </SplitPanel>
    </div>
    <div slot="right" style="height:100%;">
        <div style="width:100%;height:100%;">
            {#if selectedFromItem && selectedFromItem.size >= 0}
                {#if selectedFromItem.path.endsWith('/')}
                    <div class="folder"></div>
                    <div class="resource-label">{selectedFromItem.count} files, {Math.floor(selectedFromItem.size/1024)} KB</div>
                {:else}
                    {#if selectedFromItem.path.match(/^.*\.(log)$/)}
                        <div class="content logPanel">
                            {#await getLogHtml('/datastore/' + selectedFromItem.storeName + '/get/' + selectedFromItem.path)}
                                Loading log...
                            {:then result}
                                {@html result}
                            {:catch error}
                                Failed to load: {error.message}
                            {/await}
                        </div>
                    {:else}
                    {#if selectedFromItem.path.match(/^.*\.(te?xt|html|js|ya?ml|json|cfg|gif|jpe?g|png|svg|pdf|log)$/) && selectedFromItem.size < 1024*1024}
                        <iframe class="content" src={'/datastore/' + selectedFromItem.storeName + '/get/' + selectedFromItem.path} title={selectedFromItem.path} on:load={evt=>iframeLoaded(evt.target)}></iframe>
                    {:else}
                        <div class="file"></div>
                        <div class="resource-label">{
                            selectedFromItem.size > 2048*1024 ? Math.floor(selectedFromItem.size/(1024*1024)) + ' MB' :
                            selectedFromItem.size > 2048  ? Math.floor(selectedFromItem.size/1024) + ' KB' : selectedFromItem.size + ' bytes'
                        }</div>
                    {/if}
                    {/if}
                {/if}
            {/if}
        </div>
    </div>
</SplitPanel>

<style lang="scss">

.content {
  width: 100%;
  height: 100%;
}

.logPanel {
  white-space: pre;
  font-family: monospace;
  overflow: auto;
}

.file, .folder {
  margin-top: 20%;
  height: 20%;
  background-position: center;
  background-repeat: no-repeat;
  background-size: contain;
}

.file {
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' xml:space='preserve' viewBox='0 0 100 100'%3E%3Cpath fill='%23e0e0e0' d='M19.3 9.4v81.2h61.4V28.2L61.9 9.4z'/%3E%3Cpath fill='%23656666' d='M80.5 28 62 9.5V28z'/%3E%3Cpath fill='%23323232' d='M40.9 40.6H69v3.7H41zM30.6 50h38.5v3.8H30.6zm0 9.4h38.5v3.8H30.6zm0 9.5h38.5v3.8H30.6zm0 9.4h38.5v3.8H30.6z'/%3E%3C/svg%3E");
}
.folder {
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' xml:space='preserve' width='256' height='256'%3E%3Cg fill='none' stroke-miterlimit='10' stroke-width='0'%3E%3Cpath fill='%23e0ad31' d='m208 100.2-147.6 5.5c-4.9 0-7.7 1.8-9 6.5L20.6 217.1c-1.3 4.4-7.4 6.5-11.6 6.5a7.7 7.7 0 0 1-7.7-7.7V42.8c0-6 4.8-10.7 10.7-10.7h76c2.9 0 5.6 1.1 7.6 3.1l22 22c2 2 4.8 3.2 7.6 3.2h72c6 0 10.7 4.8 10.7 10.7V100.2z'/%3E%3Cpath fill='%23e9e9e0' d='M180.5 42.4v153.3a7 7 0 0 1-7 7H38a7 7 0 0 1-7-7V8.5a7 7 0 0 1 7-7h101.4c6.9 17 20.9 30.5 41 41z'/%3E%3Cpath fill='%23d9d7ca' d='M180.5 42.4h-34.6a6.4 6.4 0 0 1-6.5-6.4V1.4l41 41zM150.3 67H58a4.2 4.2 0 1 1 0-8.4h92.3a4.2 4.2 0 1 1 0 8.5zM150.3 86.7H58a4.2 4.2 0 1 1 0-8.4h92.3a4.2 4.2 0 1 1 0 8.4z'/%3E%3Cpath fill='%23ffc843' d='M9 223.6c4.3 0 6.5-3.2 7.8-7.7l30.9-107.7a11 11 0 0 1 10.5-8h189.3c4.6 0 7.8 4.4 6.5 8.7l-30.6 104c-1.4 5-4.4 10.8-10.7 10.7H9.1z'/%3E%3C/g%3E%3C/svg%3E");
}
.resource-label {
  text-align: center;
  white-space: nowrap;
}
.progress-bar {
  position: relative;
  display:inline-block;
  top:0.3em;
  width: 200px;
  height:1em;
  border:1px solid yellow;
}

</style>
