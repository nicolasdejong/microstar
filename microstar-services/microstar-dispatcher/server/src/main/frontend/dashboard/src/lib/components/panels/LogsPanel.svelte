<!--suppress CssUnusedSymbol -->
<script lang="ts">
    import {nameToServicesStore, serviceNamesStore} from "../../stores/ServicesStore";
    import {addRawLog} from "../../utils/Logging";
    import {fetchText} from "../../utils/Network";
    import {defer} from "../../utils/Utils";
    import {EventReceiver} from "../../utils/EventReceiver";
    import {onDestroy} from "svelte/internal";

    let selectedServiceName = '', selectedServiceInstances, selectedServiceInstance;
    let logPanel : HTMLDivElement;
    const eventReceiver = EventReceiver.unconnected()
        .onLog(data => addRawLog(data.log, getLogPanel()));

    $: selectedServiceInstances = (() => {
        const runningServices = ((($nameToServicesStore || {})[selectedServiceName]) || []).filter(s => s.state === 'RUNNING');
        if(runningServices.length == 1) {
            if(selectedServiceInstance.instanceId !== runningServices[0].instanceId) {
                defer(() => { selectedServiceInstance = runningServices[0]; updateLog(); });
            } }
        return runningServices;
    })();

    onDestroy(() => {
        eventReceiver.close();
    });

    function updateLog() : void {
        console.log('updateLog()');
        eventReceiver.disconnect();
        logPanel.innerHTML = 'Loading... from ' + '/' + selectedServiceInstance.instanceId + '/logging/current';
        fetchText('/' + selectedServiceInstance.instanceId + '/logging/current').then(logText => {
            logPanel.innerHTML = '';
            addRawLog(logText, getLogPanel());
            eventReceiver.connect(selectedServiceInstance.instanceId);
        });
    }
    function getLogPanel() {
        if(!logPanel) logPanel = document.querySelector('.logPanel'); // gets reset on 'live' reload
        return logPanel;
    }
    function clicked(evt) {
        let button;
        if(!button && evt.target.classList.contains('expand-button')) button = evt.target;
        if(!button && evt.target.previousElementSibling?.classList?.contains('expand-button')) button = evt.target.previousElementSibling;
        if(!button && evt.target.parentElement?.previousElementSibling?.classList?.contains('expand-button')) button = evt.target.parentElement.previousElementSibling;
        if(button) {
            let expandable = button.nextElementSibling;
            while(expandable && !expandable.classList.contains('expandable')) expandable = expandable.nextElementSibling;
            if(!expandable) return;
            button.classList.toggle('expanded');
            expandable.classList.toggle('expanded', button.classList.contains('expanded'));
        }
    }
</script>


<div class="container" on:click={evt => clicked(evt)}>
    <div class="logSelectionBar">
        Service: <select bind:value={selectedServiceName}>
            <option value="">&lt;select service&gt;</option>
            {#each $serviceNamesStore || [] as serviceName}
                <option value={serviceName}>{serviceName}</option>
            {/each}
        </select>
        Version: <select bind:value={selectedServiceInstance} on:change={() => updateLog()}>
             <option value="">&lt;select version&gt;</option>
            {#each selectedServiceInstances as service}
                <option value={service}>{service.id.group + '/' + service.id.version + '/ACTIVE'}</option>
            {/each}
        </select>
    </div>
    {#if selectedServiceName}
        <div class="logPanel" bind:this={logPanel}></div>
    {/if}
    {#if !selectedServiceName}
        <div class="initialSelectionPanel">
            <div>
                <h2>Select a service</h2>
                {#each $serviceNamesStore || [] as serviceName}
                    <li on:click={() => selectedServiceName = serviceName}>{serviceName}</li>
                {/each}
            </div>
        </div>
    {/if}
</div>


<style>
.container {
    position: relative;
    display: block;
    height: 100%;
}
.initialSelectionPanel {
    position: absolute;
    overflow: auto;
    left: 30%;
    top: 2em;
    right: 30%;
    bottom: 10px;
    display: flex;
    align-items: center;
    font-size: 135%;
    line-height: 1.8em;
}
.initialSelectionPanel > div {
    display: block;
    width: fit-content;
    margin: auto;
    background-color: var(--background-color);
    padding-bottom: 5em;
    cursor: pointer;
}
.initialSelectionPanel > div li:hover {
    color: var(--text-color-hover);
    background-color: var(--background-color-hover);
}
.logSelectionBar {
    position: absolute;
    box-sizing: border-box;
    top: 0;
    left: 0;
    width: 100%;
    height: calc(2em - 3px);
    overflow: hidden;
    border-bottom: 2px solid var(--border-color);
    padding: 0.20em 0 0.25em 0.25em;
    white-space: nowrap;

}
select {
    font-size: 100%;
}
.logPanel {
    position: absolute;
    left: 0;
    top: calc(2em - 2px);
    width: 100%;
    height: calc(100% - 2em);
    padding-left: 1.2em;
    padding-bottom: 0.5em; /* so the horizontal slider is not over the text */
    overflow: auto;
    white-space: pre;
    font-family: monospace;
}
</style>
