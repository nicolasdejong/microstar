<!--suppress TypeScriptUnresolvedFunction, TypeScriptUnresolvedVariable, JSPrimitiveTypeWrapperUsage, JSUnresolvedVariable -->
<!-- Unfortunately the Svelte plugin for IntelliJ (by Jetbrains) does not
     support Typescript very well so many things are not recognized
     (like the Date or Math objects, array methods, etc) so these
     'errors' need to be suppressed.
-->
<script lang="js" context="module">
  import Prism from "prismjs";
  import {addLanguageYaml} from "../../prism-yaml.js";

  addLanguageYaml(Prism);
</script>

<script lang="ts">
  import {CodeJar}     from "@novacbn/svelte-codejar";
  import SelectionList from "../various/SelectionList.svelte";
  import type ListItem from "../various/SelectionList.svelte";
  import type List     from "../various/SelectionList.svelte";
  import type {Service} from "../../types/Services";
  import {tooltip}     from "../various/tooltip";
  import {settingsFilesStore} from "../../stores/SettingsFilesStore";
  import {versionsStore} from "../../stores/VersionsStore";
  import {settingsUsersStore} from "../../stores/SettingsUsersStore";
  import {servicesStore} from "../../stores/ServicesStore"
  import {doFetch, fetchText} from "../../utils/Network";
  import {asBoolean, defer, timeToAge, timeToText, delay} from "../../utils/Utils";
  import {onMount} from "svelte";
  import Spinner from "../various/Spinner.svelte";
  import Modal from "../modals/Modal.svelte";
  import Trashcan from "../various/Trashcan.svelte";

  const highlight = (code: string, syntax: string) => Prism.highlight(code, Prism.languages[syntax], syntax);

  let showingVersions      = asBoolean(localStorage.showingVersions);
  let showingDeletedFiles  = asBoolean(localStorage.showingDeletedFiles);
  let showingRelativeTimes = asBoolean(localStorage.showingRelativeTimes);

  let selectedFile: ListItem;
  let settingsFiles: List ;
  let hasDeletedFiles: boolean;
  let oldSelectedFile: ListItem;
  let filesList: SelectionList;

  const versionTemplate: string = '<span class="version $del $ren">$version</span><span class="time" title="$timeTooltip">$time</span>$user';
  let selectedVersion: ListItem;
  let versions: List;
  let highestVersion = 0;

  let loadedFileText = '';
  let editorSourceText = '';
  let alteredFileText = loadedFileText;
  let fileHasDraft = false;
  let isEditable = true;
  let isHistory = false;
  let isError = false;
  let isTextAltered = false;
  let isDraftSelected = false;
  let editorDomNode: HTMLElement;
  let showingHelp = false;
  let showingUsersOfFile = false;
  let serviceToRefresh = '-';
  const selectAServiceLabelDefault = 'Select a service'
  let selectAServiceLabel = selectAServiceLabelDefault;
  let serviceInstanceToShow = '';
  let combinedSettingsText = '';
  let combinedSettingsTitle = '';
  let combinedSettingsFiltered = true;
  let combinedSettingsDiv: HTMLDivElement;
  let serviceToCreateSettingsFor: Service;

  let canBeValidated = true;
  let validationError = null;
  let isValidating = false;
  let verifyLabel = 'Verify';

  onMount(() => {
    const interval = setInterval(updateTimeInVersionItems, 1000);
    return () => clearInterval(interval);
  });

  $: showingDeletedFiles,  localStorage.showingDeletedFiles = settingsFiles.showDeleted = !!showingDeletedFiles;
  $: showingVersions,      localStorage.showingVersions = !!showingVersions;
  $: showingRelativeTimes, localStorage.showingRelativeTimes = !!showingRelativeTimes, versionsStore.updated();
  $: selectedFile,         handleFileChange();
  $: selectedVersion,      handleVersionChange();
  $: combinedSettingsFiltered, renderCombinedSettings();
  $: serviceToCreateSettingsFor, createFileFor(serviceToCreateSettingsFor);

  settingsFilesStore.subscribe(items => {
    settingsFiles = <List><unknown>{
      items: Array.from(items || []).map(sf => ({
        name: sf.name,
        text: sf.name,
        isDeleted: sf.isDeleted,
        canBeDeleted: !sf.isDeleted,
        canBeRenamed: !sf.isDeleted,
        canBeRestored: sf.isDeleted
      })),
      showDeleted: showingDeletedFiles
    };
    hasDeletedFiles = (items || []).some(sf => sf.isDeleted);
  });
  versionsStore.subscribe(vData => {
    const items = vData.map(ver => ({
      name: ver.version,
      version: ver.version,
      isDeleted: false,
      canBeDeleted: false,
      canBeRenamed: false,
      canBeRestored: ver.actionType !== 'DELETED' && (ver.version >= 0 || fileHasDraft),
      datetime: new Date(ver.modifiedTime * 1000),
      actionType: ver.actionType,
      html: versionTemplate
            .replace(/\$version/g, '' + ver.version)
            .replace(/\$timeTooltip/g, timeToText(new Date(ver.modifiedTime * 1000), true))
            .replace(/\$time/g, [new Date(ver.modifiedTime * 1000)].map(date => showingRelativeTimes ? timeToAge(date) : timeToText(date))[0])
            .replace(/\$user/g, ver.user)
            .replace(/\$del/g, ver.actionType === 'DELETED' ? 'deleted' : '')
            .replace(/\$ren/g, ver.actionType === 'RENAMED' ? 'renamed' : '')
    }) );
    fileHasDraft = vData[0]?.actionType !== 'DELETED' && null !== getDraftFromLocalStorage();
    versions = <List><unknown>{ items };
    highestVersion = Math.max(...items.map(item => item.version), 0);

    updateDraftName();
  });

  function handleFileChange(): Promise<any> {
    if(selectedFile === oldSelectedFile) return;
    oldSelectedFile = selectedFile;
    selectedVersion = null; // prevent selecting the same version number as the previous selected file
    validationError = null;
    if(selectedFile?.isNew) {
      fileHasDraft = true;
      updateDraftName();
      selectedVersion = versions.items[0];
      return Promise.resolve();
    }
    return Promise.all([
        versionsStore.setFile(selectedFile?.text)
          .then(() => {
            selectedVersion = versions.items[0];
          }),
        settingsUsersStore.setFile(selectedFile?.text)
    ]);
  }
  function handleVersionChange(): void {
    isDraftSelected = selectedVersion?.version === -1;
    validationError = null;
    loadFile();
  }
  function handleEdit(newText: string): void {
    alteredFileText = newText;
    isTextAltered = alteredFileText !== loadedFileText;

    fileHasDraft = isTextAltered;
    if(!fileHasDraft) removeDraftFromLocalStorage();
    (versions.items[fileHasDraft ? 1 : 0] || {}).canBeRestored = fileHasDraft;
    updateDraftName();

    if(isTextAltered && fileHasDraft && selectedVersion?.version !== -1) { selectedVersion = versions.items[0]; isDraftSelected = true; }
    if(isDraftSelected) storeDraftInLocalStorage();
  }

  async function loadFile(): Promise<void> {
    if (!selectedFile || !selectedVersion || !selectedFile.name) {
      editorSourceText = '';
      return;
    }
    if (selectedVersion.actionType === 'DELETED') {
      editorSourceText = '';
      isHistory = true;
      isEditable = false;
      isError = false;
      setEditorEditable();
      return;
    }

    const loadVersion = selectedVersion?.version === highestVersion || selectedVersion?.version === -1 ? 0 : selectedVersion?.version;

    try {
      loadedFileText = await fetchText('/microstar-settings/file/' + selectedFile?.name + '?raw&' + (loadVersion > 0 ? ('version=' + loadVersion) : ''));
      isHistory = selectedVersion?.version !== highestVersion || (selectedVersion?.version === highestVersion && fileHasDraft);
      isEditable = !isHistory || selectedVersion?.version === -1;
      isError = false;
    } catch (failed) {
      loadedFileText = 'ERROR: ' + failed.statusText;
      isHistory = selectedVersion?.version !== highestVersion;
      isEditable = false;
      isError = true;
    } finally {
      alteredFileText = isEditable ? (getDraftFromLocalStorage() || loadedFileText) : loadedFileText;
      editorSourceText = alteredFileText;
      if (isEditable) handleEdit(editorSourceText);
      setEditorEditable();
    }
  }

  function updateTimeInVersionItems(): void {
    versions.items.filter((item: List) => item.datetime).forEach((item: List) => {
      const dateLabel = showingRelativeTimes ? timeToAge(item.datetime) : timeToText(item.datetime);
      item.html = item.html.replace(/(use:tooltip>).*?(<\/span>)/, '$1' + dateLabel + '$2');
    });
    versions = <List><unknown>{items: versions.items};
  }
  function setEditorEditable(set?: boolean) {
    if(set === undefined) set = isEditable;
    isEditable = set;
    editorDomNode?.querySelector('pre').setAttribute('contenteditable', set ? 'plaintext-only' : 'false');
  }
  function updateDraftName(): void {
    const items = versions.items;
    if(fileHasDraft && selectedFile) {
      if(!items.length || items[0].version !== -1) {
        items.unshift({
          name: '',
          version: -1,
          isDeleted: false,
          canBeDeleted: true,
          canBeRenamed: false,
          canBeRestored: false,
          html: versionTemplate
                  .replace(/\$version/g, '')
                  .replace(/\$timeTooltip/g, '')
                  .replace(/\$time/g, 'DRAFT')
                  .replace(/\$user/g, '')
                  .replace(/\$del/g, '')
        });
        versions.items = items; // so Svelte knows the array has changed
      }
    } else {
      isDraftSelected = false;
      if( items.length && items[0].version === -1) {
        items.shift();
        versions.items = items; // so Svelte knows the array has changed
        if(!versions.items.find((item: List) => item.version === selectedVersion?.version)) selectedVersion = versions.items[0];
      }
    }
  }
  function storeDraftInLocalStorage(): void {
    if(!selectedFile) return;
    localStorage[getDraftKey()] = alteredFileText;
  }
  function getDraftFromLocalStorage(): string {
    if(!selectedFile) return null;
    return localStorage[getDraftKey()] || null;
  }
  function removeDraftFromLocalStorage(): void {
    delete localStorage[getDraftKey()];
    fileHasDraft = false;
    isDraftSelected = false;
  }
  function getDraftKey(): string {
    return 'settings.' + selectedFile?.text;
  }
  function save(): void {
    const draftText = getDraftFromLocalStorage();
    doFetch('/microstar-settings/store/' + selectedFile.name, 'post', draftText)
      .then(_ => {
        oldSelectedFile = null;
        if(draftText.trim() == '') {
          refreshAllServices() // new file: let all services request settings so the server can determine who depends on this new file
            .then(() => delay(2500))
            .then(() => settingsUsersStore.refresh()) // new file, ask how often it is referenced
        }
        return handleFileChange();
      });
  }

  async function deleteFile() {
    removeDraftFromLocalStorage();
    await doFetch('/microstar-settings/delete/' + selectedFile.name, 'DELETE');
    selectedVersion = null;
    await refreshStores();
  }
  function restoreFile(item: ListItem) {
    removeDraftFromLocalStorage();
    alteredFileText = null;
    selectedVersion = null;
    doFetch('/microstar-settings/restore/' + item.name, 'POST').then(refreshStores)
  }
  async function renameFile(item: ListItem, newName: string) {
    if(!newName || !item) return refreshStores();
    if(item.isNew) {
      await doFetch('/microstar-settings/store/' + newName, 'post', '');
    } else {
      await doFetch('/microstar-settings/rename/' + item.name + "?newName=" + newName, 'POST');
    }
    selectedFile.name = newName; // keep same item selected when refreshing
    await refreshStores();
  }

  function deleteDraft() {
    removeDraftFromLocalStorage();
    updateDraftName();
    loadFile();
  }
  function restoreVersion(item: ListItem) {
    if(item.version === highestVersion) deleteDraft();
    else {
      removeDraftFromLocalStorage();
      doFetch('/microstar-settings/restore/' + selectedFile.name + '?version=' + item.version, 'POST').then(refreshStores);
    }
  }

  async function refreshStores() {
    await settingsFilesStore.refresh();
    await versionsStore.refresh();
    selectedFile = settingsFiles.items.find((sf: ListItem) => sf.name === selectedFile?.name);
    selectedVersion = versions.items[0];
  }

  function createNew() {
    if(selectedFile?.isNew) return;
    const newFile: ListItem = <ListItem><unknown>{
      canBeDeleted: true,
      canBeRenamed: true,
      isNew: true,
      data: null,
      html: '',
      text: '',
      name: ''
    };

    settingsFiles.items.unshift(<any>newFile);
    settingsFiles = settingsFiles; // force update
    selectedFile = newFile;
    defer(() => {
      filesList.setSelection(<any>newFile);
      filesList.rename();
    }, 100);
  }

  async function refreshService(serviceName: string): Promise<void> {
    if(serviceName === '-') return;
    selectAServiceLabel = 'refreshing';
    const toRefresh = (serviceName || '').replace(/\//g, '_');
    try {
      await doFetch('/microstar-settings/refresh/' + (toRefresh || ''), 'POST');
      selectAServiceLabel = 'Done';
    } catch {
      selectAServiceLabel = 'Failed';
    }
    setTimeout(() => selectAServiceLabel = selectAServiceLabelDefault, 1500);
  }
  function refreshAllServices(): Promise<void> {
    return refreshService('');
  }

  function validate() {
    isValidating = true;

    // First validate on Dispatcher, when that succeeds validate on all other services
    fetchText('/validate-settings/', 'POST', alteredFileText)
      .then(errorText => {
        if(errorText) throw errorText;//{ validationError = errorText; return Promise.reject(errorText); }
        return Promise.all(
          servicesStore.get()
            .filter(service => service.id.name != 'dispatcher')
            .map(service => fetchText([service.instanceId, 'validate-settings'], 'POST', alteredFileText)
                    .catch(_ => '') // Errors are ignored (validation errors are returned with status 200 anyway)
            )
        );
      })
      .then(results => {
        handleValidationResults(results);
      })
      .catch(error => {
        console.log('ERROR: ' + error);
        if(typeof error === 'object') error = JSON.stringify(error);
        handleValidationResults([error]);
      })
      .finally(() => isValidating = false)

  }
  function handleValidationResults(results: string[]): void {
    const error = results.filter(s=>s).join('\n---\n');
    validationError = error ? error.replace(/^\w+:/, '').replace(/\w+: .?UNKNOWN/g, '').trim() : null;
    verifyLabel = error ? 'ERROR' : 'OK'; setTimeout(() => verifyLabel = 'Verify', 1500);
  }

  function showCombinedSettingsOfInstance(instanceId: string, serviceId: string): void {
    fetchText([instanceId, 'combined-settings'], 'get', null, { accept:'text/plain' })
      .then(yamlText => {
        combinedSettingsTitle = 'Combined settings of ' + serviceId + ' (' + instanceId + ')';
        combinedSettingsText = yamlText;
        defer(renderCombinedSettings);
      });
  }
  function renderCombinedSettings(): void {
    if(combinedSettingsDiv)
      combinedSettingsDiv.innerHTML = Prism.highlight(filterCombinedSettingsText(combinedSettingsText), Prism.languages['yaml'], 'yaml');
  }
  function filterCombinedSettingsText(text: string): string {
    return !combinedSettingsFiltered ? text : ('\n' + text + '\nsentinel')
            .replace(/(?<=\n)[A-Z_].*?\n/g, '')
            .replace(/(?<=\n)com:\n +sun:.*?\n(?=\S)/s, '')
            .replace(/(?<=\n)(file|ftp|http|java|jdk|jna|jnidispatch|line|native|os|path|socksNonProxyHosts|sun|user):.*?\n(?=\S)/gs, '')
            .replace(/sentinel$/, '')
            .trim()
            ;
  }

  function createFileFor(service: Service): void {
    if(!service) return;

    fetchText(service.instanceId + '/default-settings')
      .catch(error => console.warn('Unable to get default settings of', service.id.combined, 'error:', error))
      .then((yaml: string) => {
        const existingSettingsFile = settingsFiles.items.find((sf: ListItem) => new RegExp(service.id.name + '.ya?ml').test(sf.name));
        if(existingSettingsFile) {
          selectedFile = existingSettingsFile;
          return yaml;
        } else {
          const newName = service.id.name + '.yaml';
          return doFetch('/microstar-settings/store/' + newName, 'post', '')
                  .then(refreshStores)
                  .then(() => selectedFile = settingsFiles.items.find((sf: ListItem) => sf.name === newName))
                  .then(() => {
                    refreshAllServices() // new file: let all services request settings so the server can determine who depends on this new file
                      .then(() => delay(2500)) // service refresh is external async so just wait a bit before asking settings service for file usage
                      .then(() => settingsUsersStore.refresh());
                    return ''; // don't return previous promise or loading the file will take too long
                  })
                  .then(() => yaml);
        }
      })
      .then(yaml => {
        setTimeout(() => handleEdit(yaml), 500); // don't know exactly when just selected file is loaded
      })
      .finally(() => {
        serviceToCreateSettingsFor = null;
      })
    ;
  }
</script>

<div class="container">

  {#if showingHelp}
    <Modal on:close={() => showingHelp = false}>
      <div slot="header">
        About configuration files
      </div>
      The loading order of Microstar configuration files is inserted into the
      Spring configuration loading and is very similar to default Spring configuration:
      <ul>
        <li>... default Spring configuration like application{'{-profile}'}.yml ...</li>
        <li><b>services.yml</b></li>
        <li><b>services-{'{profile}'}.yml</b> (multiple times when multi-profile)</li>
        <li><b>{'{service}'}.yml</b></li>
        <li><b>{'{service}'}-{'{version}'}.yml</b></li>
        <li><b>{'{service}'}-{'{profile}'}.yml</b> (multiple times when multi-profile)</li>
        <li><b>{'{serviceGroup}'}-{'{service}'}.yml</b></li>
        <li><b>{'{serviceGroup}'}-{'{service}'}-{'{profile}'}.yml</b> (multiple times when multi-profile)</li>
      </ul>

      <ul>
        <li>Each file overrides previously loaded settings</li>
        <li>Ignores configurations that have a "spring.config.activate.on-profile" that not includes given profiles</li>
        <li>Includes configurations that are in the "spring.config.import", recursively</li>
        <li>Both .yml and .yaml are supported</li>
      </ul>

      The settings service will provide the combined settings from the above
      files which will be included in the Spring Boot configuration (it is
      inserted just after the system properties, meaning that system
      properties and up override these settings. Also see 'Externalized
      Configuration' in the Spring documentation.)<br>
      <br>
      The settings service remembers what files are touched when creating the
      combined settings for each service when requested. This way it knows
      what services to tell to update their settings when a file changes.
      This however does not work for new files so there is the option to
      tell specific services to refresh their settings.<br>
      <br>
      Secret values can be encrypted. Go to the 'encryption' tab to encrypt
      values and add those values prefixed by {'{cipher}'} (or !cipher! which
      does not require quotes around the whole value in yaml). Make sure all
      services use the same encPassword (and using the default encPassword
      compromises security). (key: encryption.encPassword)<br>
    </Modal>
  {/if}

  {#if combinedSettingsText}
    <Modal on:close={() => combinedSettingsText = ''}>
      <div slot="header">{combinedSettingsTitle.split(/\(/,2)[0]}</div>
      <div slot="subHeader">{'(instance ' + combinedSettingsTitle.split(/\(/,2)[1]}</div>
      <div class="combined-settings" bind:this={combinedSettingsDiv}></div>
      <div slot="footer" class="options">
        <input type="checkbox" bind:checked={combinedSettingsFiltered}>Filtered
      </div>
    </Modal>
  {/if}

  <div class="files">
    <div class="header">
      Files
      {#if !showingVersions}
        <button style="float:right;"
                on:click={() => showingVersions=true}>V&gt;</button>
      {/if}
      {#if hasDeletedFiles }
        <button class="switch" class:on={showingDeletedFiles} style="float:right;margin-right:0.5em;"
                title="show/hide deleted files" use:tooltip
                on:click={() => showingDeletedFiles=!showingDeletedFiles}>D</button>
      {/if}
      <button style="float:right;margin-right:1em;" on:click={_ => showingHelp = true}>?</button>
      <button style="float:right;margin-right:1em;" on:click={_ => createNew()}>+</button>
    </div>
    <div class="list"> <!-- settingsFiles -->
      <SelectionList list={settingsFiles}
                     on:lastSelection={evt => selectedFile = evt.detail.item}
                     bind:this={filesList}
                     on:rename={evt => renameFile(evt.detail.item, evt.detail.newName)}
                     on:delete={_ => deleteFile()}
                     on:restore={evt => restoreFile(evt.detail.item)}
      ></SelectionList>
    </div>
    <div class="footer">
      <div class="select">
        <span>Refresh</span>
        <select bind:value={serviceToRefresh} on:change={() => { refreshService(serviceToRefresh); serviceToRefresh='-';}}>
          <option value="-">{selectAServiceLabel}</option>
          <option value="">All</option>
          {#each $servicesStore as service}
            {#if service.instanceId && service.state === 'RUNNING' }
              <option value={service.id.combined}>{service.id.combined}</option>
            {/if}
          {/each}
        </select>
      </div>
      <div class="select">
        <span>Show</span>
        <select bind:value={serviceInstanceToShow} on:change={() => {
          showCombinedSettingsOfInstance(serviceInstanceToShow.split(/,/,2)[0], serviceInstanceToShow.split(/,/,2)[1]);
          serviceInstanceToShow='';
        }}>
          <option value="">Select an instance</option>
          {#each $servicesStore as service}
            {#if service.instanceId && service.state === 'RUNNING' }
              <option value={service.instanceId + "," + service.id.combined}>
                {service.id.combined} ({service.instanceId.substring(0,8) + '...'}
              </option>
            {/if}
          {/each}
        </select>
      </div>
      <div class="select">
        <span>Create</span>
        <select bind:value={serviceToCreateSettingsFor}>
          <option value={null}>Select a file to create</option>
          {#each $servicesStore as service}
            {#if service.instanceId && service.state === 'RUNNING' }
              <option value={service}>
                {service.id.name}.yml ({service.id.combined})
              </option>
            {/if}
          {/each}
        </select>
      </div>
    </div>
  </div>


  <div class="versions" class:hidden={!showingVersions}>
    <div class="header">
      Versions
      <button style="float:right;" on:click={() => showingVersions=false}>&lt;</button>
      <button class="switch" class:on={showingRelativeTimes} style="float:right;margin-right:0.5em;"
              title="Show absolute or relative times" use:tooltip
              on:click={() => showingRelativeTimes=!showingRelativeTimes}>R</button>
    </div>
    <div class="list">
      <SelectionList list={versions}
                     on:lastSelection={evt => selectedVersion = evt.detail.item}
                     on:delete={_ => deleteDraft()}
                     on:restore={evt => restoreVersion(evt.detail.item)}
      ></SelectionList>
    </div>
  </div>


  <div class="editor-pane" class:noVersions={!showingVersions}>
    <div class="header" style="overflow:visible;z-index:10;">
      {#if !showingVersions && isDraftSelected}<b>(DRAFT <Trashcan tooltip="Delete draft" on:click={() => deleteDraft()}></Trashcan>)</b>&nbsp;&nbsp;&nbsp;{/if}
      {#if selectedFile?.isDeleted}<b>This file is deleted. Selecting file or a version provides an option to restore</b>&nbsp;&nbsp;&nbsp;{/if}
      {#if selectedFile && !selectedFile.isDeleted}
        This settings file is used by
        <button on:click={() => showingUsersOfFile = true} class="used-by">{$settingsUsersStore.length}</button>
        service{$settingsUsersStore.length === 1 ? '' : 's'}
        {#if showingUsersOfFile}
          <Modal on:close={() => showingUsersOfFile=false}>
            <div slot="header">
              Services using {selectedFile.name}
            </div>
            <ul>
              {#each $settingsUsersStore as service}
                <li>{service}</li>
              {/each}
            </ul>
          </Modal>
        {/if}
      {/if}
      {#if !selectedFile}&nbsp;{/if}
    </div>
    <div class="editor" class:uneditable={!isEditable}
                        class:error={isError}
                        class:history={isHistory}
                        class:altered={isDraftSelected && isTextAltered}
                        class:validation-error={!!validationError}
                        bind:this={editorDomNode}>
      {#if !selectedFile}<div style="position:absolute;left:45%;top:45%;">Select a file</div>{/if}
      <CodeJar syntax="yaml" {highlight} value={editorSourceText} withLineNumbers={!isError}
               on:change={evt => { handleEdit(evt.detail.value); } }></CodeJar>
    </div>
    <div class="validation-error-text" class:validation-error={!!validationError}>
      <button class="close-button" on:click={() => validationError = null}>x</button>
      {validationError}
    </div>
    <div class="footer">
      <!-- these warnings are false positives but can't be suppressed by IntelliJ (WEB-54911) -->
      <button class="with-border" style="float:right;margin-left:1em;"
              disabled={!isDraftSelected || !isTextAltered || isValidating}
              on:click={_ => save()}
        >Save</button>
      <button class="with-border" style="float:right;margin-left:1em;"
              on:click={() => validate()}
              disabled={!canBeValidated || !isDraftSelected || !isTextAltered || isValidating}
        >
        {#if isValidating}
          Verifying <Spinner size="25"></Spinner>
        {:else}
          {verifyLabel}
        {/if}
      </button>
    </div>
  </div>
</div>

<style lang="scss">

.container {
  position: relative;
  height:100%;

  :global .modal {
    max-width: 75vw;
    max-height: 80vh;
    .combined-settings { white-space: pre; }
    .options { position: absolute; }
  }

  .files       { position: absolute; left: 0;    width: 22em; border-right: 1px solid var(--border-color); white-space: nowrap; z-index:2; }
  .versions    { position: absolute; left: 22em; width: 20em; border-right: 1px solid var(--border-color); white-space: nowrap; z-index:1; }
  .editor-pane { position: absolute; left: 42em; right: 0; height: 100%; }

  .versions.hidden { left: 0; }
  .editor-pane.noVersions { left: 22em; }

  .files { border-bottom-left-radius: 0.5rem; }
  .files, .versions { background-color: var(--background-color); }

  .versions, .editor-pane { transition: all; transition-duration: 0.8s; }

  .files .header, .versions .header { button { border-width: 0; } }

  :global .trashcan {
    transition: all;
    transition-duration: 0.25s;
    margin:0 0.5em 0.5em 0.5em;
    &:hover { transform: scale(1.3); }
  }

  button.used-by {
    font-size: 100% !important;
    padding: 0 0.25em !important;
    background-color: transparent;
    border-color: var(--border-color-hover);
    &:hover {
      background-color: var(--background-color);
      border-color: var(--border-color);
    }
  }

  .files .header, .versions .header, .editor-pane .header {
    height: 1.8em;
    border-bottom: 1px solid var(--border-color);
    background-color: var(--background-color-faded);
    padding: 0.2em;
  }
  .editor-pane .header { padding-left: 0.5em; }
  .files .list {
    height: calc(100% - 1.8em - 3.1em);
    overflow: auto;
  }
  .files .footer {
    display: flex;
    flex-direction: column;
    height: 4.6em;
    background-color: var(--background-color-faded);
    border-top: 1px solid var(--border-color);
    border-bottom-left-radius: 0.5rem;
    padding: 0 0.2em;

    .select {
      span:first-child { width: 3.5em; display: inline-block; }
      select {
        width: calc(100% - 3.5em - 1em - 2px);
        flex: 1;
      }
    }
  }

  button.with-border {
    border-color:var(--border-color);
    padding:0 0.5em;
  }
  button.switch {
    background-color: var(--background-color);
    color           : var(--text-color);

    &.on {
      background-color: var(--text-color);
      color:            var(--background-color);
    }
  }

  .header button {
    font-size:80%;padding:0;
  }

  .files {
    display: flex;
    flex-direction: column;
    height: 100%;
    .list {
      overflow-x: hidden;
      overflow-y: auto;
    }
  }

  .versions {
    display: flex;
    flex-direction: column;
    height: 100%;
    .list {
      overflow-x: hidden;
      overflow-y: auto;
    }
    // items are rendered inside another Svelte component, so css should be shared
    :global.version { display: inline-block; width: 2.75em; font-weight: bold; text-align: right; padding-right: 1.5em; }
    :global.time    { display: inline-block; width: 6em; }
    :global.version.deleted:after { content: 'X'; font-size: 150%; color: red; position: absolute; margin-left: 0.1em; margin-top:0.05em; }
    :global.version.renamed:after { content: 'R'; font-size: 130%; color: #AA0; position: absolute; margin-left: 0.2em; margin-top:0; }
  }

  .editor-pane {
    display: flex;
    flex-direction: column;
    .editor { height: calc(100% - 1.8em); overflow: auto; }
    .validation-error-text { height: 0; overflow: auto; }

    .editor.validation-error { height: calc(100% - 1.8em - 25%); }
    .validation-error-text.validation-error {
      position: relative;
      height: 25%;
      border-top: 1px solid var(--border-color);
      padding: 0.5em;

      .close-button {
        position:absolute;
        right: 0.2em;
        top: 3px;
        background: transparent;
        line-height: 0.8em;
        border: 1px solid var(--border-color-hover);
        &:hover { border: 1px solid var(--border-color); }
      }
    }

    .footer {
      height: 1.8em;
      border-top: 1px solid var(--border-color);
      background-color: var(--background-color-faded);
      padding: 0.2em 0.5em 0.2em 0.2em;
      border-bottom-right-radius: 0.5rem;
    }

    :global(.codejar-wrap) {
      width: 100%;
      height: 100%;
    }
    :global(.codejar-linenumbers) {
      mix-blend-mode: initial !important;
    }
    :global(.codejar-linenumbers > div) {
      padding-right: 0.5em;
      text-align: right;
      line-height: 1.5em; // default is different than the pre.language-yaml
      color: var(--text-color) !important;
    }
    :global(.language-yaml) {
      font-size: 90%;
      border-width: 0;
      margin: 0;
      padding-top: 0 !important;
      padding-right: 0 !important;
      padding-bottom: 0 !important;
      width: 100%;
      height: 100%;
      white-space: pre !important;
    }
  }
}
@media (prefers-color-scheme: dark) {
  .container .editor-pane {
    :global, :global pre { background-color: #000; }
    .editor.history { :global, :global pre { background-color: #222; } }
    .editor.altered { :global, :global pre { background-color: #002; } }
    .validation-error { background-color: #a00; color: white; }
  }
}
@media (prefers-color-scheme: light) {
  .container .editor-pane {
    :global, :global pre { background-color: #fff; }
    .editor.history { :global, :global pre { background-color: #eee; } }
    .editor.altered { :global, :global pre { background-color: #f8f8ff; } }
    .validation-error { background-color: #fcc; color: black; }
  }
}
</style>