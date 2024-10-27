<script type="ts">
    import {servicesStore} from '../../../stores/ServicesStore';
    import type {Service} from "../../../types/Services";
    import {deleteService, startService, startServiceAndStopOthers} from "./serviceUtils";
    import {selectedServiceStore} from "../../../stores/SelectedServiceStore";
    import {dedupArray} from "../../../utils/Utils";

    let dormantServices: Service[];
    let lineServices = {}; // line between groups of dormants with the same name
    let services: Service[];

    servicesStore.subscribe((newServices: Service[]) => updateList(newServices));
    selectedServiceStore.subscribe(() => updateList($servicesStore));
    function updateList(newServices: Service[]): void {
        services = newServices;
        dormantServices = newServices
            .filter(s => (s.state === 'DORMANT' || s.state === 'RUNNING')
                && (!s.id.combined.endsWith("-IDE")) // don't include services running from IDE
                && (!$selectedServiceStore || s.id.name === $selectedServiceStore.id.name) // selected service
            );
        dedupArray(dormantServices, s => s.id.combined);
        let name = '';
        lineServices = {};
        dormantServices.forEach(service => {
            if(service.id.name !== name) {
                name = service.id.name;
                lineServices[service.id.combined] = 1;
            }
        })
    }
    function hasRunningVersions(service: Service) {
        return (services || []).filter(s=>s.state !== 'DORMANT' && s.id?.name === service?.id?.name).length > 0;
    }
    function isDispatcher(service: Service) {
        return service.id.name === 'microstar-dispatcher';
    }
    function deleteDormantService(service: Service) {
        dormantServices = dormantServices.filter(s => s !== service);
        deleteService(service);
    }

</script>

<div id="dormantServicesPanel">
    <table class="services">
        <tr>
            <th class="name">Service Name</th>
            <th class="version">Version</th>
            <th class="group">Group</th>
            <th class="actions">Actions</th>
        </tr>
        {#each (dormantServices || []) as service}
            <tr class:line={lineServices[service.id.combined]}>
                <td class="name"   >{service.id.name}</td>
                <td class="version">{service.id.version}</td>
                <td class="group"  >{service.id.group === 'main' ? '' : service.id.group}</td>
                <td class="actions" style="text-align:center;">
                    <button class="noborder" class:disabled={isDispatcher(service)}
                                                on:click={() => startService(service)}>
                        {hasRunningVersions(service) ? 'add' : 'start'}</button>

                    <button class="noborder" class:disabled={!hasRunningVersions(service)}
                                                on:click={() => startServiceAndStopOthers(service)}
                                                   title={isDispatcher(service)?"Only works in combination with Watchdog":""}>
                        replace</button>

                     <button class="noborder" on:click={() => deleteDormantService(service)}>
                         delete</button>
                </td>
            </tr>
        {/each}
    </table>
</div>

<style lang="scss">
  table.services {
    position:relative;
    display: inline-table;
    border-collapse: collapse;
    margin: 0.5em 1em 0 1em;

    th { padding: 0 0.5em; border-bottom: 2px solid var(--border-color); }
    tr:nth-child(2n+3) td { background-color: var(--background-color); }
    tr:nth-child(2) td { padding-top: 0.5em; }

    td { padding: 0.1em 0.5em; border-right: 1px solid var(--border-color); user-select: none; }
    tr.dormant td { color: #999; cursor: default !important; }
    td:last-child { border-right: 0; }

    tr.line td { border-top: 1px solid var(--border-color); }
    tr:hover td:not(.wide-title) { background-color: var(--background-color-hoverbg); }

    td.version { text-align: right; }
    td.group   { text-align: center; }
    td.actions { cursor: default; button { background-color: transparent; } }

    button.disabled { pointer-events: none; opacity: 0; }
  }
</style>