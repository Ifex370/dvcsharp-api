using Application.DTOs.Requests;
using Application.DTOs.Responses;
using Application.Services;
using Application.Services.Abstract;
using Application.Wrappers;
using AutoMapper;
using MediatR;
using Newtonsoft.Json;
using Shared.ResultModels;
using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.Linq;
using System.Net.Http.Headers;
using System.Text;
using System.Threading.Tasks;
namespace Application.Features.Customer.Query;
public class CustomerChannelsQueryRequest
{
    public string? AccountNumber { get; set; }
    public string? RequestId { get; set; }
}
[ExcludeFromCodeCoverage]
public class CustomerChannelsQuery : IRequest<Response<List<CustomerChannelResponse>>>
{
    public string? ClientId { get; set; }
    public string? ProductId { get; set; }
    public string? AccountNumber { get; set; }
    public string? RequestId { get; set; }
}
public class CustomerChannelsQueryHandler : IRequestHandler<CustomerChannelsQuery, Response<List<CustomerChannelResponse>>>
{
    readonly IMapper _mapper;
    readonly ICustomerChannelsService _customerChannelsService;
    readonly ISerilogLoggerRepository _serilogLoggerRepository;
    public CustomerChannelsQueryHandler(IMapper mapper, ICustomerChannelsService customerChannelsService, ISerilogLoggerRepository serilogLoggerRepository)
    {
        _mapper = mapper ?? throw new ArgumentNullException(nameof(mapper));
        _customerChannelsService = customerChannelsService ?? throw new ArgumentNullException(nameof(customerChannelsService));
        _serilogLoggerRepository = serilogLoggerRepository ?? throw new ArgumentNullException(nameof(serilogLoggerRepository));
    }
    public async Task<Response<List<CustomerChannelResponse>>> Handle(CustomerChannelsQuery request, CancellationToken cancellationToken)
    {
        var customerChannelRequest = _mapper.Map<CustomerChannelsRequest>(request);
        var customerChannelsServiceResponse = await _customerChannelsService.GetCustomerChannelsByAccountNumberAsync(customerChannelRequest, cancellationToken);
        var status = (customerChannelsServiceResponse != null && customerChannelsServiceResponse.Any()) 
            ? Resp.successful 
            : Resp.noRecordFound;
        var res = Resp.FormatResponse(status);
        var response = new Response<List<CustomerChannelResponse>>(data: customerChannelsServiceResponse!, message: res.description, code: res.code);
        Environment.SetEnvironmentVariable("requestId", request.RequestId);
        Environment.SetEnvironmentVariable("clientId", request.ClientId);
        Environment.SetEnvironmentVariable("productId", request.ProductId);
        var requestString = JsonConvert.SerializeObject(request);
        var methodName = nameof(this.Handle);
        _serilogLoggerRepository.LogAlternateChannels(requestString, response, methodName);
        return response;
    }
}
