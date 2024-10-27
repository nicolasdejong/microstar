<script type="ts">
    import {servicesStore} from '../../../stores/ServicesStore';
    import {EventReceiver} from "../../../utils/EventReceiver";
    import {timeToAge} from "../../../utils/Utils";
    import type {ProcessInfo, Service} from "../../../types/Services";
    import {onMount} from "svelte";
    import {tooltip} from "../../various/tooltip";
    import Tabs from "../../various/Tabs.svelte";
    import DormantServicesPanel from "./DormantServicesPanel.svelte";
    import {forgetService, gcService, restartService, startService, stopService} from "./serviceUtils";
    import {selectedServiceStore} from "../../../stores/SelectedServiceStore";
    import StarInfoPanel from "./StarInfoPanel.svelte";
    import JarUploadButton from "./JarUploadButton.svelte";
    import {fetchData} from "../../../utils/Network";

    let services: Service[] = [];
    let servicesInList: Service[] = [];
    let hideRunningServices = localStorage.hideRunningServices == 'true';
    let hideMicroStarServices = localStorage.hideMicroStarServices == 'true';
    let serviceFilterText: string;
    let tabSelected: any; // tabItems[n].value
    let tabItems = [
        { label: "Dormants",  value: 1, component: DormantServicesPanel, disabled: false },
        { label: "Star Info", value: 2, component: StarInfoPanel, disabled: false}
    ];


    EventReceiver.get()
        .onRegistered  (_ => servicesStore.refresh())
        .onUnregistered(_ => servicesStore.refresh())
        .onStarting    (_ => servicesStore.refresh())
        .onAddedJar    (_ => servicesStore.refresh())
        .onRemovedJar  (_ => servicesStore.refresh())
        .onProcessInfo (infos => handleServiceProcessInfos(infos))
        ;

    servicesStore.subscribe((newServices: Service[]) => {
        services = newServices;
        updateServiceProcessInfo();
    });
    $: hideRunningServices, localStorage.hideRunningServices = hideRunningServices ? 'true' : 'false';
    $: hideMicroStarServices, localStorage.hideMicroStarService = hideMicroStarServices ? 'true' : 'false', services = services;
    $: services, updateServicesInList();

    onMount(() => {
        updateLayout();
        const serviceRefreshInterval = setInterval(() => services = services, 1000);
        return () => {
            clearInterval(serviceRefreshInterval);
        }
    });

    function updateServicesInList() {
        let list = [];
        let activeServices = services.filter(s=>s.state != 'DORMANT').map(s=>s.id.name);
        services.forEach(service => {
            if(!service?.id?.name || !isServiceVisible(service)) return;
            if(service.state !== 'DORMANT' || !activeServices.includes(service.id.name)) {
                activeServices.push(service.id.name);
                list.push(service);
            }
        });
        servicesInList = list;
    }
    function isServiceVisible(service: Service): boolean {
        if(hideMicroStarServices && ('' + service.id.name).startsWith('microstar')) return false;
        return !serviceFilterText
            || ('' + service.id.combined).includes(serviceFilterText)
            || ('' + service.instanceId ).includes(serviceFilterText)
            ;
    }
    function updateServiceProcessInfo(serviceToUpdate?: Service): void {
        const fetches = [];
        (serviceToUpdate ? [serviceToUpdate] : services)
            .filter(service => !!service.instanceId)
            .forEach(service => {
               fetches.push(fetchData({ url: service.instanceId + '/processInfo', showError: false })
                   .then((processInfo: ProcessInfo) => service.processInfo = processInfo)
               );
            });
        Promise.all(fetches).then(() => services = services); // trigger update when all fetches are done
    }
    function handleServiceProcessInfos(map: Record<string,ProcessInfo>): void {
        servicesInList.forEach(service => {
           const processInfo = map[service.instanceId];
           if(processInfo) service.processInfo = processInfo;
        });
        services = services; // trigger UI refresh
    }
    function isDispatcher(service: Service) {
        return service.id.name === 'microstar-dispatcher';
    }

    let draggingSplit = false;
    let servicesPanel: HTMLDivElement;
    let detailsPanel: HTMLDivElement;

    function splitDragStart(evt: MouseEvent): void {
        draggingSplit = true;
        updateLayout(evt.clientY);
    }
    function splitDrag(evt: MouseEvent): void {
        if(draggingSplit) updateLayout(evt.clientY);
    }
    function splitDragStop(evt: MouseEvent): void {
        if(draggingSplit) updateLayout(evt.clientY);
        draggingSplit = false;
    }
    function updateLayout(y?: number): void {
        let splitPos = 0.5;
        if(y === undefined) {
            splitPos = parseFloat(localStorage.servicesSplitPos || '50');
        } else {
            const parentHeight = (<any>servicesPanel.parentNode.parentNode).offsetHeight;
            splitPos = Math.round(1000 * (y - 60) / parentHeight) / 10;
        }
        splitPos = Math.min(90, Math.max(5, splitPos));
        localStorage.servicesSplitPos = splitPos;

        servicesPanel.style.height = 'calc(' + splitPos + '% - 3px)';
        detailsPanel .style.height = 'calc(' + (100-splitPos) + '% - 23px)';
    }
</script>

<svelte:window on:mousemove={evt=>splitDrag(evt)} on:mouseup={evt=>splitDragStop(evt)}/>

<div class="container" class:dragging={draggingSplit}>

    <div id="servicesPanel" bind:this={servicesPanel} on:click={evt => { if(evt.target === servicesPanel) selectedServiceStore.set(null); }} on:keydown={()=>{}}>
        <table class="services">
            <span style="position:absolute; right: -0.5em; top: -2.0em;">
                <label style="margin-right:2em;">
                    <input type=checkbox bind:checked={hideMicroStarServices}>Hide MicroStar services
                </label>
                <span style="position:relative;">
                    <input type="text" placeholder="filter"
                           bind:value={serviceFilterText}
                           style="width:10em;padding-right:1em;border:1px solid var(--border-color);margin-right:1em;"/>
                    <button style="position:absolute;margin-left:-2.1em;margin-top:-0.15em;background:transparent;border-width:0;"
                            on:click={() => serviceFilterText=''}
                    >&#10006;</button>
                </span>
                <JarUploadButton></JarUploadButton>
            </span>
            <tr>
                <th class="name">Service Name</th>
                <th class="version">Version</th>
                <th class="group">Group</th>
                <th class="running">Running</th>
                <th class="req24h" title="Number of requests in the last 24h" use:tooltip>24h</th>
                <th class="req10m" title="Number of requests in the last 10m" use:tooltip>10m</th>
                <th class="memory">ProcMem</th>
                <th class="heap">MetaSpace</th>
                <th class="heap">Heap</th>
                <th class="heapUsed" title="Used Heap">HeapUse</th>
                <th class="heap" title="Minimal heap use over the last 5 minutes (min sawtooth)">HeapMin</th>
                <th class="pid">PID</th>
                <th class="actions">Actions</th>
            </tr>
            {#each (servicesInList || []) as service}
                <tr class:running={service.state === 'RUNNING'}
                    class:dormant={service.state === 'DORMANT'}
                    class:starting={service.state === 'STARTING'}
                    class:selected={service === $selectedServiceStore}
                    on:click={() => selectedServiceStore.set(service)}>
                    <td class="name"   >{service.id.name}</td>
                    <td class="version">{service.id.version}</td>
                    <td class="group"  >{service.id.group === 'main' ? '' : service.id.group}</td>
                    <td class="running">{service.state === 'RUNNING' ? timeToAge(service.runningSince) : ''}</td>
                    <td class="req24h" >{service.state === 'RUNNING' ? service.requestCount24h : ''}</td>
                    <td class="req10m" >{service.state === 'RUNNING' ? service.requestCount10m : ''}</td>
                    <td class="memory" >{(service.processInfo?.residentMemorySize || '').replace(/^(\d+M).*$/, '$1') || ''}</td>
                    <td class="heap"     title={service.processInfo?.metaSpace || 'no data'}>{(service.processInfo?.metaSpace || '').replace(/^(\d+M).*$/, '$1') || ''}</td>
                    <td class="heap"     title={service.processInfo?.heapSize || 'no data'} on:click={() => gcService(service)} style="cursor:pointer;">{(service.processInfo?.heapSize || '').replace(/^(\d+M).*$/, '$1')}</td>
                    <td class="heapUsed" title={service.processInfo ? service.processInfo.heapUsed + ' used (' + service.processInfo?.heapUsedPercent + '%)' : 'no data'}>{(service.processInfo?.heapUsed || '').replace(/^(\d+M).*$/, '$1') || ''}</td>
                    <td class="heap"     title={service.processInfo?.minHeapUsed || 'no data'}>{(service.processInfo?.minHeapUsed || '').replace(/^(\d+M).*$/, '$1')}</td>
                    <td class="pid"    >{(service.processInfo?.pid || '')}</td>
                    <td class="actions" style="text-align:center;">
                        {#if service.state === 'DORMANT'}
                            <button class="noborder" on:click={() => startService(service)}>start</button>
                        {/if}
                        {#if service.state === 'RUNNING'}
                            <button class="noborder" on:click={() => stopService(service)}
                                                        title={isDispatcher(service)?"If a Watchdog is running, it will start the newest available Dispatcher after this one stopped.":""}
                            >stop</button>
                        {/if}
                        {#if service.state === 'RUNNING' && !isDispatcher(service)}
                            <button class="noborder" on:click={() => restartService(service)}>restart</button>
                        {/if}
                        {#if service.state === 'STARTING'}
                            <button class="noborder" on:click={() => forgetService(service)}>forget</button>
                        {/if}
                    </td>
                </tr>
            {/each}
        </table>
    </div>
    <div id="splitPanel" on:mousedown={evt=>splitDragStart(evt)}>
        <div class="line"></div>
        <div class="bottom"></div>
        <div class="title">
            {#if $selectedServiceStore}
                Selected: {$selectedServiceStore.id.name}/{$selectedServiceStore.id.version}/{$selectedServiceStore.id.group}:{$selectedServiceStore.instanceId || '<not started>'}
            {:else}
                No selection
            {/if}
        </div>
        <div class="split-buttons">
            <div on:click={() => {localStorage.servicesSplitPos=5;  updateLayout();}} on:keydown={()=>{}}>&#x25B2;</div>
            <div on:click={() => {localStorage.servicesSplitPos=50; updateLayout();}} on:keydown={()=>{}}>&bull;</div>
            <div on:click={() => {localStorage.servicesSplitPos=90; updateLayout();}} on:keydown={()=>{}}>&#x25BC;</div>
        </div>
    </div>
    <div id="detailsPanel" bind:this={detailsPanel}>
        <Tabs items={tabItems} bind:activeTabValue={tabSelected}/>
    </div>

</div>

<style lang="scss">
  .container {
    padding: 0 0 0 0;
    display: block;
    height: 100%;
    overflow: hidden;

    &.dragging { user-select: none; }

    #servicesPanel { overflow: auto; }
    #detailsPanel { overflow: auto; margin-top: -5px; height: 100%; background-color: var(--background-color-faded); }
    #splitPanel {
      position: relative;
      height: 30px;
      position: relative;
      background-color: transparent;
      cursor: n-resize;
      .line {
        position:absolute;
        width:100%; height:6px; top:30%;
        background-color: var(--background-color-hover);
      }
      .bottom {
        position:absolute;
        width:100%; height:calc(70% - 6px); top:calc(30% + 6px);
        background-color: var(--background-color-faded);
      }
      .title {
        position:absolute;
        left:0.5rem; top:-2px;
        padding: 0 0.5rem;
        font-size: 125%;
        background: var(--background-color);
        border:1px solid var(--border-color);
      }
      .split-buttons {
        position:absolute;
        right:20px; top:-2px;
        display:flex; font-size:150%;
        padding-top:5px;
        background: var(--background-color);
        border:1px solid var(--border-color);
        > div {
          height:1em;
          margin-top:-5px;
          padding-right: 5px;
          cursor: pointer;
          &:hover { color: var(--text-color-hover); }
        }
      }
    }
  }
  table.services {
    position:relative;
    display: inline-table;
    border-collapse: collapse;
    margin: 2.5em 1em 0 1em;

    th { padding: 0 0.5em; border-bottom: 2px solid var(--border-color); }
    tr:nth-child(2n+3):not(.selected) td { background-color: var(--background-color-faded); }
    tr:nth-child(2) td { padding-top: 0.5em; }

    tr.selected { background-color: var(--background-color-hoverbg); }

    td { padding: 0.1em 0.5em; border-right: 1px solid var(--border-color); user-select: none; }
    tr.dormant td { color: #999; cursor: default !important; }
    td:last-child { border-right: 0; }

    tr.newest td { border-top: 1px solid var(--border-color); }
    tr:hover td:not(.wide-title) { background-color: var(--background-color-hoverbg); }
    tr.starting {
      animation-duration: 1.75s;
      animation-name: animate-to-start;
      animation-iteration-count: infinite;
    }

    td.version { text-align: right; }
    td.group   { text-align: center; }
    td.running { text-align: right; }
    td.req24h  { text-align: right; }
    td.req10m  { text-align: right; }
    td.memory  { text-align: center; } th.memory   { font-size: 80%; }
    td.heap    { text-align: center; } th.heap     { font-size: 80%; }
    td.heapUsed{ text-align: center; } th.heapUsed { font-size: 80%; }
    td.pid     { text-align: center; }
    td.actions { cursor: default; button { background-color: transparent; } }
  }

  @keyframes animate-to-start {
    50% { color: #999; }
    100% { color: var(--text-color); }
  }
</style>