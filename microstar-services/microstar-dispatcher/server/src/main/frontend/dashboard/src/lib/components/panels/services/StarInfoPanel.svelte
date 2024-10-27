<script type="ts">
    import {localStarInfoStore} from "../../../stores/LocalStarInfoStore";
</script>

{#if $localStarInfoStore}
    <table class="starInfo">
        <tr>  <th colspan="2">Star info</th> </tr>
        <tr>  <td>Star name</td>      <td>{$localStarInfoStore.starName}</td>      </tr>
        <tr>  <td>Star url</td>       <td>{$localStarInfoStore.starUrl}</td>       </tr>
        <tr>  <td>Dispatcher url</td> <td>{$localStarInfoStore.dispatcherUrl}</td> </tr>
        <tr>  <td>IP-addresses</td>   <td>
            {#each ($localStarInfoStore.ipAddresses || []) as ipAddress}
                {ipAddress}<br>
            {/each}
        </td>
        </tr>
    </table>
{/if}

<style lang="scss">
  table.starInfo {
    position:relative;
    display: inline-table;
    border-collapse: collapse;
    margin: 0 1em 0 1em;
    top: 1.5em;
    th { padding: 0 0.5em; border-bottom: 2px solid var(--border-color); }
    td { padding: 0.15em 0.5em; vertical-align: top; }
    td:first-of-type { border-right: 1px solid var(--border-color); }
    tr:nth-child(2n+2) td { background-color: var(--background-color-faded); }
  }
</style>