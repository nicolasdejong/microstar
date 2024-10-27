<script type="ts">

    import {doFetch, fetchData} from "../../../utils/Network";
    import {starPropertiesStore} from "../../../stores/StarPropertiesStore";

    type JarInfo = {
        name: string;
        star: string;
    }

    let addingJar = false;
    let uploadingJars = false;
    let availableJars: JarInfo[] = [];
    let availableJarSelected = null;
    let jarFileInput: HTMLInputElement;

    $: addingJar, (() => { if(addingJar) requestAvailableJars(); })();

    function handleMouseDown(evt: MouseEvent): void { hideJarSelectionPanelWhenClickedOutside(evt); }
    function openJarsPanel(): void {
        requestAvailableJars().then(() => {
            if(availableJars.length > 0) addingJar = true;
            else jarFileInput.click();
        });
    }
    function hideJarSelectionPanelWhenClickedOutside(evt: MouseEvent): void {
        if(addingJar) {
            let target: HTMLElement = <HTMLElement>evt.target;
            while(target && !(target.classList && target.classList.contains('jarSelectionPanel'))) target = (<any>target).parentNode;
            if(!target) addingJar = false;
        }
    }

    async function requestAvailableJars(): Promise<void> {
        let response = await fetchData('/all-jars');
        availableJarSelected = null;
        const allJarInfos = response;
        const currentStarName = starPropertiesStore.getCurrentStar().name;
        const localJarsSet = allJarInfos.filter((ji: JarInfo) => ji.star === currentStarName).reduce((all:any, ji:any) => ({...all, [ji.name]: 1}), {});
        availableJars = allJarInfos.filter((ji: JarInfo) => !localJarsSet[ji.name]);
    }

    function copyJarFromOtherStar(jarInfo: JarInfo): void {
        if(!jarInfo) return;
        doFetch('/copy-jar/' + jarInfo.name + "?fromStar=" + jarInfo.star, 'POST')
            .then(_ => {
                const index = availableJars.findIndex(ji => ji.name === jarInfo.name);
                if(index >= 0) { availableJars.splice(index, 1); availableJars = availableJars; /*trigger Svelte*/ }
            });
    }
    function onJarUploadSelected(evt: any): void {
        const files = evt.target.files;
        const formData = new FormData();
        if(!files || !files[0]) return;
        Array.from(files).forEach((file: File) => formData.append(file.name, file));
        uploadingJars = true;
        doFetch('/jar', 'POST', formData)
            .finally(() => { uploadingJars = false; });
    }

</script>

<svelte:window on:mousedown={evt => handleMouseDown(evt)}/>

<button style="width:5em;"
        class:uploading={uploadingJars}
        on:click={() => openJarsPanel()}
        disabled={addingJar}
>Add jar</button>
<div class="jarSelectionPanel" class:visible="{addingJar === true}">
    <button style="position:absolute;right:0.2em;zoom:0.8;" on:click={()=>{jarFileInput.click(); addingJar = false;}}>Upload</button>
    <input style="display:none" type="file" accept=".jar" multiple on:change={(e)=>onJarUploadSelected(e)} bind:this={jarFileInput} >
    &nbsp; {availableJars.length} jar files available on other stars
    <div class="jars" style="max-height: 15em; overflow: auto;">
        {#each availableJars as jarInfo}
            <div class="jar"
                 class:selected={availableJarSelected === jarInfo}
                 on:click={() => availableJarSelected = (availableJarSelected === jarInfo ? null : jarInfo)}
                 on:dblclick={() => copyJarFromOtherStar(jarInfo)}
                 on:keydown={() => {}}
            >{jarInfo.name}</div>
        {/each}
    </div>
    <div style="height:1.5em;padding:0;border-top:1px solid var(--border-color);">
        <button style="margin-left:1em;padding:0 1em;" on:click={() => copyJarFromOtherStar(availableJarSelected)} title="Or double-click jar to add">Add Jar</button>
        <button style="margin-right:1em;float:right;padding:0 1em;" on:click={() => addingJar = false} title="Or click outside the panel">Close</button>
    </div>
</div>

<style lang="scss">
  .jarSelectionPanel {
    position:absolute;
    right:0;
    padding: 0;
    border:1px solid var(--border-color);
    background-color:var(--background-color);
    opacity: 0;
    pointer-events: none;
    transition-property: all;
    transition-duration: 0.5s;
    &.visible {
      z-index: 10;
      opacity: 1;
      pointer-events: all;
    }
    .jars {
      padding: 0;
      border-top: 1px solid var(--border-color);
      .jar {
        padding: 0 0.5em 0 0.5em;
        white-space: nowrap;
        &:hover { background-color: var(--background-color-hover); }
        &.selected { background-color: var(--background-color-hover); }
      }
    }
  }
  button.uploading {
    pointer-events: none;
    opacity: 0.5;
  }
</style>