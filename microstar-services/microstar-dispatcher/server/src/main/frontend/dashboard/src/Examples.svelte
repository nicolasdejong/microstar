<div>

<table>
    <tr>
        <th class="name">Service Name</th>
        <th class="version">Version</th>
        <th class="group">Group</th>
        <th class="running">Running</th>
        <th class="req24h" title="Number of requests in the last 24h">24h</th>
        <th class="req10m" title="Number of requests in the last 10m">10m</th>
        <th class="traffic">Traffic%</th>
        <th class="actions">Actions</th>
    </tr>
    <tr>
        <td class="name">Dispatcher</td>
        <td class="version">3</td>
        <td class="group"></td>
        <td class="running">3h</td>
        <td class="req24h">123</td>
        <td class="req10m">12</td>
        <td class="traffic disabled" title="Part of traffic for <service>.&#13;&#10;Click to change.&#13;&#10;When total goes over 100%, others will be decreased.">100%</td>
        <td class="actions">
            <button disabled>start</button>
            <button>stop</button>
            <button disabled>delete</button>
        </td>
    </tr>
    <tr>
        <td class="name">Authorization</td>
        <td class="version">1</td>
        <td class="group"></td>
        <td class="running">1h5m</td>
        <td class="req24h">5</td>
        <td class="req10m">2</td>
        <td class="traffic">100%</td>
        <td class="actions">
            <button disabled>start</button>
            <button>stop</button>
            <button disabled>delete</button>
        </td>
    </tr>
    <tr>
        <td class="name">Authorization</td>
        <td class="version">2</td>
        <td class="group">STORY1234</td>
        <td class="running">23m</td>
        <td class="req24h">1</td>
        <td class="req10m">1</td>
        <td class="traffic">100%</td>
        <td class="actions">
            <button disabled>start</button>
            <button>stop</button>
            <button disabled>delete</button>
        </td>
    </tr>
    <tr>
        <td class="name">Search</td>
        <td class="version">8</td>
        <td class="group"></td>
        <td class="running">23m</td>
        <td class="req24h">1</td>
        <td class="req10m">1</td>
        <td  class="traffic disabled">100%</td>
        <td class="actions">
            <button disabled>start</button>
            <button>stop</button>
            <button disabled>delete</button>
        </td>
    </tr>
    <tr class="dormant">
        <td class="name">Search</td>
        <td class="version">9</td>
        <td class="group">STORY1234</td>
        <td class="running"></td>
        <td class="req24h"></td>
        <td class="req10m"></td>
        <td class="traffic disabled"></td>
        <td class="actions">
            <button>start</button>
            <button disabled>stop</button>
            <button>delete</button>
        </td>
    </tr>
    <tr>
        <td class="name">Settings</td>
        <td class="version">1</td>
        <td class="group"></td>
        <td class="running">3h</td>
        <td class="req24h">20</td>
        <td class="req10m">3</td>
        <td class="traffic disabled"></td>
        <td class="actions">
            <button disabled>start</button>
            <button>stop</button>
            <button disabled>delete</button>
        </td>
    </tr>
</table>

</div>

<style lang="scss">
    div { padding: 1em; }
    table {
        border-collapse: collapse;

        tr:nth-child(2n+3) td { background-color: var(--background-color-faded); }
        tr td:last-child { background-color: transparent; }
        tr:first-child th { border-bottom: 2px solid var(--border-color); }
        tr:nth-child(2) td { padding-top: 0.5em; }

        td { padding: 0.1em 0.5em; border-right: 1px solid var(--border-color); user-select: none; }
        td.disabled, tr.dormant td { color: #999; cursor: default !important; }
        td:last-child { border-right: 0; }

        td.version { text-align: right; }
        td.group   { text-align: center; }
        td.running { text-align: right; }
        td.req24h  { text-align: right; }
        td.req10m  { text-align: right; }
        td.traffic { text-align: right; cursor: pointer; }
        td.actions {                    cursor: default; }
    }
</style>
