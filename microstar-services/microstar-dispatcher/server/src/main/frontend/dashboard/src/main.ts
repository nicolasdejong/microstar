import './app.scss'
import App from './App.svelte'

declare global {
  interface Array<T> {
    unique(): Array<T>;
  }
}
if (!Array.prototype.unique) {
  Array.prototype.unique = function<T>(this: T[]): T[] { return [...new Set(this)]; }
}


const app = new App({
  target: document.getElementById('app')
})

export default app

// wasReloaded = (window.performance.getEntriesByType('navigation').map(nav => nav['type']).includes('reload'));
