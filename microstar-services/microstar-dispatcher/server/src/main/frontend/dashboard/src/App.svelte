<script lang="ts">
    import LogsPanel from "./lib/components/panels/LogsPanel.svelte"
    import ServicesPanel from "./lib/components/panels/services/ServicesPanel.svelte"
    import SettingsPanel from "./lib/components/panels/SettingsPanel.svelte"
    import Tabs from "./lib/components/various/Tabs.svelte"
    import ErrorModal from "./lib/components/modals/ErrorModal.svelte";
    import {EventReceiver} from "./lib/utils/EventReceiver";

    import {dashboardSettingsStore} from "./lib/stores/DashboardSettingsStore.js";
    import {servicesStore} from "./lib/stores/ServicesStore";
    import PasswordModal from "./lib/components/modals/PasswordModal.svelte";
    import EncryptionPanel from "./lib/components/panels/EncryptionPanel.svelte";
    import {userStore} from "./lib/stores/UserStore.js";
    import {userPasswordStore} from "./lib/stores/UserPasswordStore.js";
    import {starPropertiesStore} from "./lib/stores/StarPropertiesStore";
    import StaticDataPanel from "./lib/components/panels/StaticDataPanel.svelte";
    import {clearUserData} from "./lib/utils/Network.js";
    import {longclick, shortclick} from "./lib/utils/actions";
    import DataStoresAccessPanel from "./lib/components/panels/DataStoresAccessPanel.svelte";

    let items = [ // no const so Svelte can be notified of changes by doing items = items
        { label: "Services",    value: 1, component: ServicesPanel,   disabled: false },
        { label: "Settings",    value: 2, component: SettingsPanel,   disabled: false },
        { label: "Static-Data", value: 3, component: StaticDataPanel, disabled: false },
        { label: "DataStores",  value: 4, component: DataStoresAccessPanel, disabled: false },
        { label: "Encryption",  value: 5, component: EncryptionPanel, disabled: false },
        { label: "Logs",        value: 6, component: LogsPanel,       disabled: false },
    ];
  let selectedTabValue : number = parseInt(localStorage.selectedTabValue || '1');
  let delayedTrue = false;
  $: localStorage.selectedTabValue = selectedTabValue;

  setTimeout(() => delayedTrue = true, 1500);

  servicesStore.subscribe(services => {
      const runningServiceNames = services.filter(service => service.state === 'RUNNING').map(s=>s.id.name);

      // Later this should be replaced with a mechanism where each service provides its own panel
      // which then automatically will be missing when the service is not running.
      items.find(item => item.label == 'Settings'   ).disabled = !runningServiceNames.includes('microstar-settings');
      items.find(item => item.label == 'Static-Data').disabled = !runningServiceNames.includes('microstar-statics');
      items = items; // trigger Svelte update
  })

  EventReceiver.get()
      .connect()
      .onNewFrontendSettings(dashboardSettingsStore.refresh)
      .onStarsChanged(starPropertiesStore.refresh);

  function errorModalClosed() {
      if(window['onErrorModalClose']) {
          const onErrorModalClose = window['onErrorModalClose'];
          delete window['onErrorModalClose'];
          onErrorModalClose.apply();
      }
  }
</script>


<ErrorModal on:close={() => errorModalClosed()}></ErrorModal>
<PasswordModal></PasswordModal>

{#if $starPropertiesStore.stars?.length > 1}
    <div class="starSelector">
        Server:
        {#each $starPropertiesStore.stars as star}
            <li class:selected={$starPropertiesStore?.currentName === star.name}
                class:unavailable={!star.isActive}
                on:click={()=> { if(star.isActive && star.name !== $starPropertiesStore?.currentName) { starPropertiesStore.setCurrentName(star.name); location.reload(); }}}
            ><span>{star.name}</span></li>
        {/each}
    </div>
{/if}

{#if $dashboardSettingsStore.banner}
    <div class="banner"
         style="--bgcolor: {$dashboardSettingsStore.banner?.bgcolor || 'inherit'}; --color: {$dashboardSettingsStore.banner?.color || 'inherit'};"
    >
        {$dashboardSettingsStore.banner?.title || ''}
    </div>
{/if}

{#if $userStore}
    <span class="user"
          use:longclick on:longclick={() =>   { userPasswordStore.reset(); clearUserData(); location.reload(); }}
          use:shortclick on:shortclick={() => { userPasswordStore.reset(); clearUserData(); location.href = '/sso/logout'; }}
          title="log out">{($userStore.email||$userStore.name||'logged in').split(/@/)[0].toLowerCase()}</span>
{/if}

{#if ($userStore || {}).isAdmin}
  <Tabs {items} bind:activeTabValue={selectedTabValue}/>
{:else}
    {#if delayedTrue}
        <div class="notAllowed">
          You don't have sufficient rights to access the admin dashboard
        </div>
    {/if}
{/if}

<!--suppress CssUnresolvedCustomProperty -->
<style lang="scss">
.banner {
    position: absolute;
    width: fit-content;
    left: 50%;
    transform: translateX(-50%);
    padding: 0 1em;
    background-color: var(--bgcolor);
    color: var(--color);
    font-size: 120%;
}
.starSelector {
    position: absolute;
    top: 0.3em;
    right: 10em;
    li {
      display: inline-block;
      padding: 0 0.5em;
      border: 1px solid var(--border-color-hover);
      color: var(--text-color-label);
      cursor: pointer;
      user-select: none;
      &:hover {
        // color: var(--text-color-hover);
        border: 1px solid var(--border-color-hover);
        color: var(--text-color);
      }
      &.selected {
        color: var(--text-color);
        font-weight: bold;
        background-color: var(--background-color-faded);
      }
      &.unavailable {
        cursor: not-allowed;
        color: var(--text-color-faded);
        span { pointer-events: none; }
      }
    }
}
.user {
    position: absolute;
    right: 1em;
    top: 0.3em;
    cursor: pointer;
}
.notAllowed {
    position: absolute;
    left: 0;
    top: 30%;
    width: 100%;
    text-align: center;
    font-size: 125%;
}
</style>