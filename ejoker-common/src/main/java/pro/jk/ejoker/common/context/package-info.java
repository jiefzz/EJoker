/**
 * 这个包是为了构建一个简单的IoC容器，最初的想法是是实现一下类似spring的容器，但是spring太复杂了，
 * <br /> 这个是一个非常简单的实现，仅实现了依赖注入和初始化，无其他附加功能
 * <br /> spring有 controller service component 和 repository 等4中類型的注入標記，分別有不用的意義
 * <br /> 這裡就是一個 {@link pro.jk.ejoker.common.context.annotation.context.Dependence} 意為託管注入，不區分具體角色
 * <br /> 这个简单的IoC只提供非常朴素的扫描方法来扫描类和探查元数据，且在IoC初始化前要主动执行，有复杂需求的，尽量就用spring吧
 */
package pro.jk.ejoker.common.context;