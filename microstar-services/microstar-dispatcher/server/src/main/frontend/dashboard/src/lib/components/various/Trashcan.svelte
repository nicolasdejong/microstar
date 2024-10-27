<script type="ts">
  import {tooltip as Tooltip} from './tooltip';

  export let revert = false;
  export let tooltip: string = null; // prevent blank tooltips
  export let animate = false;
</script>

<span title={tooltip} use:Tooltip class:animate={animate} on:click|stopPropagation>
  <span class="trashcan" class:revert={revert}><span></span><i></i></span>
</span>

<style>
    .trashcan {
        background: var(--text-color);;
        width: 66px;
        height: 80px;
        display: inline-block;
        margin:0 auto;

        position: relative;
        border-bottom-right-radius: 6px;
        border-bottom-left-radius: 6px;
        zoom: 0.15;
        top: 1.6em;
    }
    .trashcan.revert { background: var(--background-color); }
    .trashcan span {
        position: absolute;
        height: 12px;
        background: var(--text-color);;
        top: -19px;
        left: -10px;
        right: -10px;

        border-top-left-radius: 10px;
        border-top-right-radius: 10px;
        transform: rotate(0deg);
        transition: transform 250ms;
        transform-origin: 19% 100%;
    }
    .trashcan.revert span { background: var(--background-color); }
    .trashcan span:after {
        content: '';
        position: absolute;
        width: 27px;
        height: 7px;
        background: var(--text-color);;
        top: -10px;

        border-top-left-radius: 10px;
        border-top-right-radius: 10px;
        transform: rotate(0deg);
        transition: transform 250ms;
        transform-origin: 19% 100%;
        left: 27px;
    }
    .trashcan.revert span:after { background: var(--background-color); }


    .trashcan i {
        position:relative;
        width: 5px;
        height:50px;
        background: var(--background-color);
        display:block;
        margin:14px auto;
        border-radius: 5px;
    }
    .trashcan.revert i { background: var(--text-color); }
    .trashcan i:after {
        content: '';
        width: 5px;
        height: 50px;
        background: var(--background-color);
        position: absolute;
        left: -18px;
        border-radius: 5px;
    }
    .trashcan.revert i:after { background: var(--text-color); }
    .trashcan i:before {
        content: '';
        width: 5px;
        height: 50px;
        background: var(--background-color);
        position: absolute;
        right: -18px;
        border-radius: 5px;
    }
    .trashcan.revert i:before { background: var(--text-color); }

    .animate .trashcan.foo:hover span {
        transform: rotate(-30deg);
        transition: transform 250ms;
    }
</style>